package com.molotov.clender.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class EventLocalDataTest {
    private lateinit var database: ClenderDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClenderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertPersistsServiceOwnedUidAndMetadataWithoutReplacingThem() = runBlocking {
        val now = Instant.parse("2026-08-07T01:02:03.000004Z")
        val repository = RoomEventRepository(database)

        val stored = repository.insert(
            event().copy(
                id = 0L,
                syncUid = "0123456789abcdef0123456789abcdef",
                createdAt = now,
                updatedAt = now
            )
        )

        assertTrue(stored.id > 0L)
        assertEquals("0123456789abcdef0123456789abcdef", stored.syncUid)
        assertEquals(now, stored.createdAt)
        assertEquals(now, stored.updatedAt)
        assertEquals(null, stored.deletedAt)
        assertEquals(stored, repository.findById(stored.id))
        assertEquals(listOf(stored), repository.observeVisible().first())
    }

    @Test
    fun updatePersistsServiceOwnedMetadataAndTombstoneIsHidden() = runBlocking {
        val repository = RoomEventRepository(database)
        val inserted = repository.insert(event().copy(id = 0L, syncUid = uid(10)))

        val updated = repository.update(
            inserted.copy(
                title = "updated",
                updatedAt = Instant.parse("2026-08-07T02:00:00Z")
            )
        )
        assertEquals(inserted.id, updated.id)
        assertEquals(inserted.syncUid, updated.syncUid)
        assertEquals(inserted.createdAt, updated.createdAt)
        assertEquals(Instant.parse("2026-08-07T02:00:00Z"), updated.updatedAt)

        val tombstoneTime = Instant.parse("2026-08-07T03:00:00Z")
        repository.update(updated.copy(updatedAt = tombstoneTime, deletedAt = tombstoneTime))
        val tombstone = repository.findById(inserted.id)
        assertNotNull(tombstone)
        assertEquals(tombstoneTime, tombstone!!.updatedAt)
        assertEquals(tombstone.updatedAt, tombstone.deletedAt)
        assertTrue(repository.observeVisible().first().isEmpty())

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.update(updated.copy(id = 999_999L)) }
        }
        Unit
    }

    @Test
    fun overlapUsesHalfOpenRangeAndFiltersTombstones() = runBlocking {
        val repository = RoomEventRepository(database)
        val rangeStart = LocalDateTime.of(2026, 8, 7, 10, 0)
        val rangeEnd = LocalDateTime.of(2026, 8, 7, 11, 0)
        val records = listOf(
            event().copy(syncUid = uid(1), startTime = rangeStart, title = "at-start"),
            event().copy(syncUid = uid(2), startTime = rangeEnd, title = "at-end"),
            event().copy(
                syncUid = uid(3),
                eventType = EventType.TIMESPAN,
                startTime = rangeStart.minusDays(1),
                endTime = rangeStart.plusMinutes(1),
                title = "cross-midnight"
            ),
            event().copy(
                syncUid = uid(4),
                eventType = EventType.TIMESPAN,
                startTime = rangeStart.minusHours(1),
                endTime = rangeStart,
                title = "ends-at-start"
            ),
            event().copy(
                syncUid = uid(5),
                startTime = rangeStart.plusMinutes(30),
                title = "deleted",
                updatedAt = Instant.parse("2026-08-07T04:00:00Z"),
                deletedAt = Instant.parse("2026-08-07T04:00:00Z")
            )
        )
        records.forEach { repository.insert(it) }

        assertEquals(
            listOf("cross-midnight", "at-start"),
            repository.findOverlapping(rangeStart, rangeEnd).map(Event::title)
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.findOverlapping(rangeEnd, rangeStart) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.findOverlapping(rangeStart, rangeStart) }
        }
        Unit
    }

    @Test
    fun syncSnapshotIncludesTombstonesAndIsUidSorted() = runBlocking {
        val repository = RoomEventRepository(database)
        repository.insert(event().copy(syncUid = uid(12), title = "later"))
        repository.insert(
            event().copy(
                syncUid = uid(2),
                title = "deleted",
                updatedAt = Instant.parse("2026-08-07T04:00:00Z"),
                deletedAt = Instant.parse("2026-08-07T04:00:00Z")
            )
        )

        assertEquals(listOf(uid(2), uid(12)), repository.syncSnapshot().map(Event::syncUid))
        assertEquals(1, repository.observeVisible().first().size)
    }

    @Test
    fun remoteApplyDetectsOnlyVisibleChangesAndRollsBackWholeBatch() = runBlocking {
        val repository = RoomEventRepository(database)
        val original = repository.insert(event().copy(syncUid = uid(1), title = "same"))
        val metadataOnly = original.copy(updatedAt = original.updatedAt.plusSeconds(1))

        assertFalse(repository.applyRemote(listOf(metadataOnly), ::newerCandidateWins))
        assertEquals(metadataOnly.updatedAt, repository.findById(original.id)?.updatedAt)

        val changed = metadataOnly.copy(
            title = "changed",
            updatedAt = metadataOnly.updatedAt.plusSeconds(1)
        )
        assertTrue(repository.applyRemote(listOf(changed), ::newerCandidateWins))
        assertEquals("changed", repository.findById(original.id)?.title)

        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_remote_batch BEFORE INSERT ON events
            WHEN NEW.sync_uid = '${uid(7)}'
            BEGIN SELECT RAISE(ABORT, 'forced transaction failure'); END
            """.trimIndent()
        )
        val validBeforeFailure = event().copy(syncUid = uid(6), title = "must-roll-back")
        val forcedFailure = event().copy(syncUid = uid(7), title = "forced-failure")
        assertThrows(SQLiteException::class.java) {
            runBlocking {
                repository.applyRemote(
                    listOf(validBeforeFailure, forcedFailure),
                    ::newerCandidateWins
                )
            }
        }
        assertEquals(null, repository.findBySyncUid(uid(6)))
        assertEquals("changed", repository.findById(original.id)?.title)
    }

    @Test
    fun schemaHasRequiredUniqueAndLookupIndexes() {
        val indexNames = database.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'events'"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

        assertTrue("idx_events_start_time" in indexNames)
        assertTrue("idx_events_sync_uid" in indexNames)
        assertTrue("idx_events_deleted_at" in indexNames)
    }

    @Test
    fun duplicateSyncUidIsRejectedWithoutReplacingOriginal() = runBlocking {
        val repository = RoomEventRepository(database)
        val original = repository.insert(event().copy(syncUid = uid(20), title = "original"))

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { repository.insert(event().copy(syncUid = uid(20), title = "duplicate")) }
        }
        assertEquals(original, repository.findBySyncUid(uid(20)))
    }

    private fun event(): Event = Event(
        id = 0L,
        eventType = EventType.REMINDER,
        title = "title",
        startTime = LocalDateTime.of(2026, 8, 7, 9, 0),
        endTime = null,
        description = "description",
        estimatedDurationMinutes = 30,
        createdAt = Instant.parse("2026-08-06T00:00:00Z"),
        syncUid = uid(1),
        updatedAt = Instant.parse("2026-08-07T00:00:00Z"),
        deletedAt = null
    )

    private fun uid(value: Int): String = value.toString(16).padStart(32, '0')
}

private fun newerCandidateWins(candidate: Event, current: Event): Boolean =
    candidate.updatedAt.isAfter(current.updatedAt)
