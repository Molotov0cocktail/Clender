package com.molotov.clender.ui.event

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.app.ScheduleMutationVersionSignal
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.data.local.ClenderDatabase
import com.molotov.clender.data.local.RoomEventRepository
import com.molotov.clender.domain.event.AddEventCommand
import com.molotov.clender.domain.event.EventPatch
import com.molotov.clender.domain.event.EventService
import com.molotov.clender.domain.event.FieldUpdate
import com.molotov.clender.domain.event.SyncUidGenerator
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class EventCrudIntegrationTest {
    private lateinit var database: ClenderDatabase
    private lateinit var repository: RoomEventRepository
    private lateinit var signal: ScheduleMutationVersionSignal
    private lateinit var service: EventService
    private val uidSequence = AtomicInteger()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClenderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomEventRepository(database)
        signal = ScheduleMutationVersionSignal()
        service = EventService(
            repository = repository,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            uidGenerator = SyncUidGenerator {
                uidSequence.incrementAndGet().toString(16).padStart(32, '0')
            },
            mutationSink = signal
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun addRefreshesExistingB1RangeAndMonthCountFlows() = runBlocking {
        val day = LocalDate.of(2026, 8, 31)
        val rangeReady = CompletableDeferred<Unit>()
        val monthCountReady = CompletableDeferred<Unit>()
        val rangeUpdates = Channel<List<Event>>()
        val monthCountUpdates = Channel<Map<LocalDate, Int>>()
        val rangeCollector = launch {
            var initialEmission = true
            repository.observeRange(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).collect {
                if (initialEmission) {
                    assertTrue(it.isEmpty())
                    initialEmission = false
                    rangeReady.complete(Unit)
                } else {
                    rangeUpdates.send(it)
                }
            }
        }
        val monthCountCollector = launch {
            var initialEmission = true
            service.observeMonthCounts(java.time.YearMonth.from(day)).collect {
                if (initialEmission) {
                    assertTrue(it.isEmpty())
                    initialEmission = false
                    monthCountReady.complete(Unit)
                } else {
                    monthCountUpdates.send(it)
                }
            }
        }

        try {
            withTimeout(5_000) {
                rangeReady.await()
                monthCountReady.await()

                val added = service.add(reminder("added", day.atTime(9, 0)))

                assertEquals(listOf(added), rangeUpdates.receive())
                assertEquals(1, monthCountUpdates.receive()[day])
                assertEquals(1L, signal.version.value)
            }
        } finally {
            rangeCollector.cancel()
            monthCountCollector.cancel()
            withContext(NonCancellable) {
                try {
                    rangeCollector.cancelAndJoin()
                    monthCountCollector.cancelAndJoin()
                } finally {
                    rangeUpdates.close()
                    monthCountUpdates.close()
                }
            }
        }
    }

    @Test
    fun updateAcrossDatesRemovesOldObservationAndAppearsInNewDate() = runBlocking {
        val oldStart = LocalDateTime.of(2026, 8, 30, 9, 0)
        val newStart = LocalDateTime.of(2026, 8, 31, 14, 0)
        val added = service.add(reminder("move", oldStart))

        service.update(added.id, EventPatch(startTime = FieldUpdate.Set(newStart)))

        assertTrue(
            repository.findOverlapping(
                oldStart.toLocalDate().atStartOfDay(),
                oldStart.toLocalDate().plusDays(1).atStartOfDay()
            ).isEmpty()
        )
        assertEquals(
            listOf(added.id),
            repository.findOverlapping(
                newStart.toLocalDate().atStartOfDay(),
                newStart.toLocalDate().plusDays(1).atStartOfDay()
            ).map { it.id }
        )
        assertEquals(2L, signal.version.value)
    }

    @Test
    fun bothTypeConversionsPersistThroughEventService() = runBlocking {
        val start = LocalDateTime.of(2026, 8, 31, 9, 0)
        val reminder = service.add(reminder("convert", start))
        val timespan = service.update(
            reminder.id,
            EventPatch(
                eventType = FieldUpdate.Set(EventType.TIMESPAN),
                endTime = FieldUpdate.Set(start.plusHours(2))
            )
        )
        assertEquals(EventType.TIMESPAN, timespan.eventType)
        assertEquals(start.plusHours(2), timespan.endTime)

        val convertedBack = service.update(
            reminder.id,
            EventPatch(eventType = FieldUpdate.Set(EventType.REMINDER))
        )
        assertEquals(EventType.REMINDER, convertedBack.eventType)
        assertNull(convertedBack.endTime)
        assertEquals(convertedBack, repository.findById(reminder.id))
        assertEquals(3L, signal.version.value)
    }

    @Test
    fun deleteCreatesTombstoneHiddenFromNormalQueriesButKeptInSyncSnapshot() = runBlocking {
        val start = LocalDateTime.of(2026, 8, 31, 9, 0)
        val added = service.add(reminder("delete", start))

        assertTrue(service.delete(added.id))

        assertTrue(
            repository.observeRange(
                start.toLocalDate().atStartOfDay(),
                start.toLocalDate().plusDays(1).atStartOfDay()
            ).first().isEmpty()
        )
        val tombstone = repository.syncSnapshot().single()
        assertEquals(added.id, tombstone.id)
        assertEquals(tombstone.updatedAt, tombstone.deletedAt)
        assertFalse(service.delete(added.id))
        assertEquals(2L, signal.version.value)
    }

    @Test
    fun failedRoomTransactionDoesNotPersistOrEmitMutation() {
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_local_insert BEFORE INSERT ON events
            BEGIN SELECT RAISE(ABORT, 'forced failure'); END
            """.trimIndent()
        )

        assertThrows(SQLiteException::class.java) {
            runBlocking {
                service.add(reminder("must roll back", LocalDateTime.of(2026, 8, 31, 9, 0)))
            }
        }

        assertEquals(0L, signal.version.value)
        assertTrue(runBlocking { repository.syncSnapshot() }.isEmpty())
    }

    private fun reminder(title: String, start: LocalDateTime) = AddEventCommand(
        eventType = EventType.REMINDER,
        title = title,
        startTime = start,
        description = "private body"
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-31T01:02:03.123456Z")
    }
}
