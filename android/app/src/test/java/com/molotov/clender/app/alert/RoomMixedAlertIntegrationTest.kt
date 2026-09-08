package com.molotov.clender.app.alert

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.alert.AlertPermissionState
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.data.local.ClenderDatabase
import com.molotov.clender.data.local.RoomEventRepository
import com.molotov.clender.domain.event.AddEventCommand
import com.molotov.clender.domain.event.EventPatch
import com.molotov.clender.domain.event.EventService
import com.molotov.clender.domain.event.FieldUpdate
import com.molotov.clender.domain.event.ScheduleMutationSink
import com.molotov.clender.domain.event.SyncUidGenerator
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class RoomMixedAlertIntegrationTest {
    private lateinit var database: ClenderDatabase
    private lateinit var repository: RoomEventRepository
    private lateinit var service: EventService
    private lateinit var runtime: EventAlertRuntime
    private val clock = MixedAlertClock()
    private val platform = MixedAlertPlatform()
    private val ledger = MixedAlertLedger()
    private val start = LocalDateTime.of(2026, 9, 8, 10, 0)

    @Before
    fun start() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ClenderDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = RoomEventRepository(database)
        service = EventService(
            repository,
            clock,
            SyncUidGenerator { UUID.randomUUID().toString().replace("-", "") },
            ScheduleMutationSink { }
        )
        runtime = EventAlertRuntime(
            repository.observeVisible(),
            repository::findById,
            platform,
            ledger,
            clock
        ) { ZoneOffset.UTC }
        runtime.refresh().join()
    }

    @After
    fun close() {
        runtime.close()
        database.close()
    }

    @Test
    fun bothEventTypesPreserveEveryMixedPolicyAndPlanCorrectKinds() = runBlocking {
        val created = EventType.entries.flatMap { type ->
            (0..7).map { bits ->
                service.add(
                    command(type).copy(
                        notificationEnabled = bits and 1 != 0,
                        alarmEnabled = bits and 2 != 0,
                        timerMinutes = if (bits and 4 == 0) {
                            0
                        } else if (bits == 7) {
                            1440
                        } else {
                            1
                        }
                    )
                )
            }
        }
        awaitEvents(created.map(Event::id).toSet())
        assertEquals(ledger.pending(), platform.scheduled.values.toSet())
        created.forEach { event ->
            assertEquals(event, repository.findById(event.id))
            val plans = ledger.pending().filter { it.token.eventId == event.id }
            val expectedKinds = buildSet {
                if (event.alarmEnabled) {
                    add(AlertKind.ALARM)
                } else if (event.notificationEnabled) {
                    add(AlertKind.NOTIFICATION)
                }
                if (event.timerMinutes > 0) add(AlertKind.TIMER)
            }
            assertEquals(expectedKinds, plans.map { it.token.kind }.toSet())
            plans.forEach { plan ->
                val expected = event.startTime.toInstant(ZoneOffset.UTC).plusSeconds(
                    if (plan.token.kind == AlertKind.TIMER) event.timerMinutes * 60L else 0
                )
                assertEquals(expected.toEpochMilli(), plan.token.triggerAtMillis)
            }
        }
    }

    @Test
    fun movingDisablingAndDeletingRevokeOldPlansAndDeliveredItems() = runBlocking {
        val event = service.add(command().copy(alarmEnabled = true, timerMinutes = 1))
        awaitEvents(setOf(event.id))
        val original = ledger.pending().map { it.token }.toSet()
        clock.now = clock.now.plusSeconds(1)
        val moved = service.update(
            event.id,
            EventPatch(startTime = FieldUpdate.Set(start.plusHours(1)))
        )
        awaitRevision(moved)
        assertTrue(platform.cancelled.containsAll(original))
        assertEquals(2, ledger.pending().size)
        val due = ledger.pending().first { it.token.kind == AlertKind.ALARM }.token
        clock.now = Instant.ofEpochMilli(due.triggerAtMillis)
        runtime.handle(due).join()
        assertEquals(listOf(due), platform.shown)
        service.update(
            event.id,
            EventPatch(
                notificationEnabled = FieldUpdate.Set(false),
                alarmEnabled = FieldUpdate.Set(false),
                timerMinutes = FieldUpdate.Set(0)
            )
        )
        withTimeout(5_000) { runtime.status.first { it[event.id] == AlertScheduleStatus.DISABLED } }
        assertTrue(ledger.pending().isEmpty())
        assertTrue(event.id in platform.dismissed)
        assertTrue(ledger.receipts().isEmpty())
        assertTrue(service.delete(event.id))
        awaitEvents(emptySet())
        assertNotNull(repository.findById(event.id)?.deletedAt)
    }

    @Test
    fun deletingAnEnabledEventCancelsEveryKindAndStaleBroadcastCannotShow() = runBlocking {
        val event = service.add(command().copy(timerMinutes = 1))
        awaitEvents(setOf(event.id))
        val tokens = ledger.pending().map { it.token }
        service.delete(event.id)
        awaitEvents(emptySet())
        clock.now = start.plusMinutes(2).toInstant(ZoneOffset.UTC)
        tokens.forEach { runtime.handle(it).join() }
        assertTrue(platform.cancelled.containsAll(tokens))
        assertTrue(platform.shown.isEmpty())
        assertTrue(ledger.pending().isEmpty())
    }

    @Test
    fun deniedPermissionsDoNotPreventRoomWritesAndSettingsRefreshRecoversPlans() = runBlocking {
        platform.permission = platform.permission.copy(notificationsAllowed = false)
        val event = service.add(command().copy(alarmEnabled = true, timerMinutes = 1))
        awaitEvents(setOf(event.id))
        assertEquals(event, repository.findById(event.id))
        assertEquals(AlertScheduleStatus.NO_NOTIFICATIONS, runtime.status.value[event.id])
        assertTrue(ledger.pending().isEmpty())
        platform.permission = platform.permission.copy(notificationsAllowed = true)
        runtime.refresh().join()
        assertEquals(AlertScheduleStatus.SCHEDULED, runtime.status.value[event.id])
        assertEquals(
            setOf(AlertKind.ALARM, AlertKind.TIMER),
            ledger.pending().map { it.token.kind }.toSet()
        )
    }

    @Test
    fun mixedDueTokensAreDeliveredOnceAndHistoricalItemsStaySilent() = runBlocking {
        val event = service.add(command().copy(alarmEnabled = true, timerMinutes = 1))
        awaitEvents(setOf(event.id))
        val tokens = ledger.pending().map { it.token }.sortedBy { it.triggerAtMillis }
        tokens.forEach {
            clock.now = Instant.ofEpochMilli(it.triggerAtMillis)
            runtime.handle(it).join()
            runtime.handle(it).join()
        }
        assertEquals(tokens, platform.shown)
        assertTrue(tokens.all { ledger.receipt(it) == AlertReceipt.DELIVERED })
        val past = service.add(command().copy(startTime = start.minusDays(1), timerMinutes = 1))
        awaitEvents(setOf(event.id, past.id))
        assertEquals(AlertScheduleStatus.PAST, runtime.status.value[past.id])
        assertFalse(ledger.pending().any { it.token.eventId == past.id })
        assertEquals(tokens, platform.shown)
    }

    private suspend fun awaitEvents(ids: Set<Long>) {
        withTimeout(5_000) { runtime.status.first { it.keys == ids } }
        runtime.refresh().join()
    }

    private suspend fun awaitRevision(event: Event) {
        withTimeout(5_000) {
            repository.observeVisible().first { rows -> rows.any { it == event } }
        }
        runtime.refresh().join()
    }

    private fun command(type: EventType = EventType.REMINDER) = AddEventCommand(
        type,
        "Synthetic mixed alert",
        start,
        endTime = if (type == EventType.TIMESPAN) start.plusHours(1) else null
    )
}

private class MixedAlertClock : Clock() {
    @Volatile
    var now: Instant = Instant.parse("2026-09-08T09:00:00Z")
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

private class MixedAlertPlatform : EventAlertPlatform {
    @Volatile
    var permission = AlertPermissionState(true, true, true, true, true)
    val cancelled: MutableSet<AlertToken> = ConcurrentHashMap.newKeySet()
    val dismissed: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    val shown = CopyOnWriteArrayList<AlertToken>()
    val scheduled = ConcurrentHashMap<AlertToken, AlertSchedule>()
    override fun permissions(): AlertPermissionState = permission
    override fun schedule(plan: AlertSchedule) {
        scheduled[plan.token] = plan
    }
    override fun cancel(token: AlertToken) {
        cancelled += token
        scheduled.remove(token)
    }
    override fun show(event: Event, token: AlertToken): Boolean {
        shown += token
        return true
    }
    override fun dismiss(eventId: Long) {
        dismissed += eventId
    }
}

private class MixedAlertLedger : AlertLedger {
    @Volatile
    private var plans = emptySet<AlertSchedule>()
    private val receipts = ConcurrentHashMap<AlertToken, AlertReceipt>()
    override fun pending(): Set<AlertSchedule> = plans
    override fun savePending(value: Set<AlertSchedule>) {
        plans = value.toSet()
    }
    override fun receipts(): Map<AlertToken, AlertReceipt> = receipts.toMap()
    override fun receipt(token: AlertToken): AlertReceipt? = receipts[token]
    override fun claim(token: AlertToken): Boolean =
        receipts.putIfAbsent(token, AlertReceipt.CLAIMED) == null
    override fun delivered(token: AlertToken) {
        receipts[token] = AlertReceipt.DELIVERED
    }
    override fun failed(token: AlertToken) {
        receipts[token] = AlertReceipt.FAILED
    }
    override fun release(token: AlertToken) {
        receipts.remove(token)
    }
}
