package com.molotov.clender.app.alert

import com.molotov.clender.alert.AlertPermissionState
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventAlertRuntimeTest {
    @Test
    fun failedAlarmRemainsFailedAcrossRefreshRestartAndDuplicateDelivery() = runBlocking {
        val event = event().copy(alarmEnabled = true)
        val fixture = Fixture(event)
        lateinit var token: AlertToken
        fixture.use {
            it.runtime.refresh().join()
            token = it.platform.scheduled.single().token
            it.clock.now = Instant.ofEpochMilli(token.triggerAtMillis)
            it.platform.showSucceeds = false
            it.runtime.handle(token).join()
            assertEquals(AlertReceipt.FAILED, it.ledger.receipt(token))
            it.platform.showSucceeds = true
            it.runtime.handle(token).join()
            it.runtime.refresh().join()
            assertTrue(it.platform.shown.isEmpty())
            assertEquals(AlertScheduleStatus.FAILED, it.runtime.status.value[1])
        }
        fixture.clock.now = Instant.ofEpochMilli(token.triggerAtMillis).minusSeconds(60)
        Fixture(event, fixture.clock, fixture.ledger).use {
            it.runtime.refresh().join()
            assertTrue(it.platform.scheduled.isEmpty())
            assertEquals(AlertScheduleStatus.FAILED, it.runtime.status.value[1])
        }
    }

    @Test
    fun asynchronousPlaybackFailurePersistsButStaleRevisionCannotPoisonNewEvent() = runBlocking {
        Fixture(event().copy(alarmEnabled = true)).use {
            it.runtime.refresh().join()
            val token = it.platform.scheduled.single().token
            it.clock.now = Instant.ofEpochMilli(token.triggerAtMillis)
            it.runtime.handle(token).join()
            it.platform.playbackFailures.emit(token)
            kotlinx.coroutines.withTimeout(2_000) {
                it.runtime.status.first { status -> status[1] == AlertScheduleStatus.FAILED }
            }
            assertEquals(AlertReceipt.FAILED, it.ledger.receipt(token))
            it.events.value = listOf(
                event().copy(
                    alarmEnabled = true,
                    startTime = event().startTime.plusHours(1),
                    updatedAt = Instant.EPOCH.plusSeconds(1)
                )
            )
            it.runtime.refresh().join()
            it.platform.playbackFailures.emit(token)
            it.runtime.refresh().join()
            assertEquals(AlertScheduleStatus.SCHEDULED, it.runtime.status.value[1])
            assertEquals(null, it.ledger.receipt(token))
        }
    }

    @Test
    fun refreshKeepsAlreadyScheduledInexactDeliveryWhileTheSystemIsLate() = runBlocking {
        val platform = FakeAlertPlatform().apply {
            permission =
                permission.copy(exactAllowed = false)
        }
        Fixture(event(), platform = platform).use {
            it.runtime.refresh().join()
            val pending = it.platform.scheduled.single()
            it.clock.now = Instant.ofEpochMilli(pending.token.triggerAtMillis).plusSeconds(60)
            it.runtime.refresh().join()
            assertFalse(pending.token in it.platform.cancelled)
            assertEquals(setOf(pending), it.ledger.pending())
            assertEquals(AlertScheduleStatus.INEXACT, it.runtime.status.value[1])
            it.runtime.handle(pending.token).join()
            assertEquals(AlertReceipt.DELIVERED, it.ledger.receipt(pending.token))
        }
    }

    @Test
    fun duePendingDeliveryStillCancelsOnChangedRevisionBlockedPermissionOrExpiredWindow() =
        runBlocking {
            listOf("revision", "permission", "disabled", "expired").forEach { scenario ->
                Fixture(event()).use {
                    it.runtime.refresh().join()
                    val token = it.platform.scheduled.single().token
                    it.clock.now = Instant.ofEpochMilli(token.triggerAtMillis).plusSeconds(60)
                    when (scenario) {
                        "revision" ->
                            it.events.value =
                                listOf(event().copy(updatedAt = Instant.EPOCH.plusSeconds(1)))

                        "permission" ->
                            it.platform.permission =
                                it.platform.permission.copy(notificationsAllowed = false)

                        "disabled" ->
                            it.events.value =
                                listOf(event().copy(notificationEnabled = false))

                        "expired" ->
                            it.clock.now =
                                Instant.ofEpochMilli(token.triggerAtMillis).plusSeconds(86_401)
                    }
                    it.runtime.refresh().join()
                    assertTrue(scenario, token in it.platform.cancelled)
                    assertTrue(it.ledger.pending().isEmpty())
                    assertTrue(it.platform.shown.isEmpty())
                }
            }
        }

    @Test
    fun futureNotificationSchedulesAndUpdateDeleteCancelPriorIdentity() = runBlocking {
        val fixture = Fixture(event())
        fixture.use {
            it.runtime.refresh().join()
            val old = it.platform.scheduled.single().token
            assertEquals(AlertKind.NOTIFICATION, old.kind)
            it.events.value = listOf(event().copy(startTime = event().startTime.plusHours(1)))
            it.runtime.refresh().join()
            assertTrue(old in it.platform.cancelled)
            assertEquals(1, it.platform.scheduled.size)
            it.events.value = emptyList()
            it.runtime.refresh().join()
            assertTrue(it.platform.scheduled.isEmpty())
            assertTrue(1L in it.platform.dismissed)
        }
    }

    @Test
    fun deniedExactPermissionBlocksAlarmWithoutDuplicateOrdinaryNotification() = runBlocking {
        Fixture(event().copy(alarmEnabled = true)).use {
            it.platform.permission = it.platform.permission.copy(exactAllowed = false)
            it.runtime.refresh().join()
            assertEquals(AlertScheduleStatus.NO_EXACT, it.runtime.status.value[1])
            assertTrue(it.platform.scheduled.isEmpty())
            it.events.value = listOf(event())
            it.runtime.refresh().join()
            assertFalse(it.platform.scheduled.single().exact)
        }
    }

    @Test
    fun permissionAndChannelDenialNeverSchedulesOrClaimsSuccess() = runBlocking {
        Fixture(event()).use {
            it.platform.permission = it.platform.permission.copy(notificationsAllowed = false)
            it.runtime.refresh().join()
            assertTrue(it.platform.scheduled.isEmpty())
            assertEquals(AlertScheduleStatus.NO_NOTIFICATIONS, it.runtime.status.value[1])
            it.platform.permission = it.platform.permission.copy(
                notificationsAllowed = true,
                notificationChannelAllowed = false
            )
            it.runtime.refresh().join()
            assertEquals(AlertScheduleStatus.CHANNEL_BLOCKED, it.runtime.status.value[1])
        }
    }

    @Test
    fun deliveryValidatesCurrentEventAndPersistsDedupAcrossRuntimeRestart() = runBlocking {
        val fixture = Fixture(event())
        lateinit var token: AlertToken
        fixture.use {
            it.runtime.refresh().join()
            token = it.platform.scheduled.single().token
            it.clock.now = Instant.ofEpochMilli(token.triggerAtMillis)
            it.runtime.handle(token).join()
            it.runtime.handle(token).join()
            assertEquals(1, it.platform.shown.size)
            assertEquals(AlertReceipt.DELIVERED, it.ledger.receipt(token))
        }
        Fixture(event(), fixture.clock, fixture.ledger, fixture.platform).use {
            it.runtime.handle(token).join()
            assertEquals(1, it.platform.shown.size)
            it.events.value = listOf(event().copy(startTime = event().startTime.plusHours(1)))
            it.runtime.handle(token).join()
            assertEquals(1, it.platform.shown.size)
        }
    }

    @Test
    fun failedNotificationReleasesClaimAndNeverReportsDelivered() = runBlocking {
        Fixture(event()).use {
            it.runtime.refresh().join()
            val token = it.platform.scheduled.single().token
            it.clock.now = Instant.ofEpochMilli(token.triggerAtMillis)
            it.platform.showSucceeds = false
            it.runtime.handle(token).join()
            assertEquals(null, it.ledger.receipt(token))
            assertEquals(AlertScheduleStatus.FAILED, it.runtime.status.value[1])
            it.platform.showSucceeds = true
            it.runtime.handle(token).join()
            assertEquals(AlertReceipt.DELIVERED, it.ledger.receipt(token))
        }
    }

    @Test
    fun pastSchedulesStaySilentButStopActionStillDismisses() = runBlocking {
        Fixture(event().copy(startTime = LocalDateTime.of(2026, 9, 8, 8, 0))).use {
            it.runtime.refresh().join()
            assertTrue(it.platform.scheduled.isEmpty())
            assertEquals(AlertScheduleStatus.PAST, it.runtime.status.value[1])
            it.runtime.handle(
                AlertPlanFactory.tokens(it.events.value.single(), ZoneOffset.UTC).single(),
                stop = true
            )
                .join()
            assertTrue(1L in it.platform.dismissed)
        }
    }

    @Test
    fun timerUsesElapsedMinutesAcrossDstAndZeroIsOff() {
        val event = event().copy(
            startTime = LocalDateTime.of(2026, 3, 8, 1, 30),
            timerMinutes = 90
        )
        assertEquals(
            Instant.parse("2026-03-08T08:00:00Z"),
            AlertPlanFactory.timerDeadline(event, ZoneId.of("America/New_York"))
        )
        assertEquals(
            null,
            AlertPlanFactory.timerDeadline(event.copy(timerMinutes = 0), ZoneOffset.UTC)
        )
    }

    @Test
    fun rebootWithPersistedScheduleRestoresTheClearedPlatform() = runBlocking {
        val ledger = MemoryAlertLedger()
        Fixture(event(), ledger = ledger).use { it.runtime.refresh().join() }
        Fixture(event(), ledger = ledger).use {
            it.runtime.refresh().join()
            assertEquals(1, it.platform.scheduled.size)
        }
    }

    @Test
    fun runningTimerIsStillScheduledAndExtremeDeadlineFailsOnlyItsOwnEvent() = runBlocking {
        val running = event().copy(
            startTime = LocalDateTime.of(2026, 9, 8, 8, 30),
            timerMinutes = 60
        )
        Fixture(running).use {
            it.runtime.refresh().join()
            assertEquals(AlertKind.TIMER, it.platform.scheduled.single().token.kind)
            val extreme = event().copy(
                id = 2,
                startTime = LocalDateTime.of(9999, 12, 31, 23, 59),
                timerMinutes = 1440
            )
            it.events.value = listOf(running, extreme)
            it.runtime.refresh().join()
            assertEquals(AlertScheduleStatus.FAILED, it.runtime.status.value[2])
            assertEquals(AlertScheduleStatus.SCHEDULED, it.runtime.status.value[1])
        }
    }

    @Test
    fun unchangedRefreshDoesNotStopAnAlreadyDeliveredAlarm() = runBlocking {
        Fixture(event().copy(alarmEnabled = true)).use {
            it.runtime.refresh().join()
            assertEquals(1, it.platform.scheduled.size)
            val token = it.platform.scheduled.single().token
            it.clock.now = Instant.ofEpochMilli(token.triggerAtMillis)
            it.runtime.handle(token).join()
            it.runtime.refresh().join()
            assertEquals(1, it.platform.shown.size)
            assertTrue(it.platform.dismissed.isEmpty())
        }
    }
}

private class Fixture(
    event: Event,
    val clock: MutableAlertClock = MutableAlertClock(),
    val ledger: MemoryAlertLedger = MemoryAlertLedger(),
    val platform: FakeAlertPlatform = FakeAlertPlatform()
) : AutoCloseable {
    val events = MutableStateFlow(listOf(event))
    val runtime =
        EventAlertRuntime(events, { id ->
            events.value.find { it.id == id }
        }, platform, ledger, clock) {
            ZoneOffset.UTC
        }

    override fun close() {
        runtime.close()
        assertFalse(runtime.scopeJob.isActive)
        assertTrue(runtime.scopeJob.isCompleted)
    }
}

private class MutableAlertClock(var now: Instant = Instant.parse("2026-09-08T09:00:00Z")) :
    Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

private class MemoryAlertLedger : AlertLedger {
    private var plans = emptySet<AlertSchedule>()
    private val receipts = mutableMapOf<AlertToken, AlertReceipt>()
    override fun pending(): Set<AlertSchedule> = plans
    override fun savePending(value: Set<AlertSchedule>) {
        plans = value
    }
    override fun receipts(): Map<AlertToken, AlertReceipt> = receipts.toMap()
    override fun receipt(token: AlertToken): AlertReceipt? = receipts[token]
    override fun claim(token: AlertToken): Boolean {
        if (token in receipts) return false
        receipts[token] = AlertReceipt.CLAIMED
        return true
    }
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

private class FakeAlertPlatform : EventAlertPlatform {
    override val playbackFailures = MutableSharedFlow<AlertToken>()
    var permission = AlertPermissionState(true, true, true, true, true)
    val scheduled = mutableSetOf<AlertSchedule>()
    val cancelled = mutableSetOf<AlertToken>()
    val shown = mutableListOf<AlertToken>()
    val dismissed = mutableSetOf<Long>()
    var showSucceeds = true
    override fun permissions(): AlertPermissionState = permission
    override fun schedule(plan: AlertSchedule) {
        scheduled.removeAll { it.token == plan.token }
        scheduled += plan
    }
    override fun cancel(token: AlertToken) {
        cancelled += token
        scheduled.removeAll { it.token == token }
    }
    override fun show(event: Event, token: AlertToken): Boolean {
        if (showSucceeds) shown += token
        return showSucceeds
    }
    override fun dismiss(eventId: Long) {
        dismissed += eventId
    }
}

private fun event(): Event = Event(
    1, EventType.REMINDER, "Synthetic reminder", LocalDateTime.of(2026, 9, 8, 10, 0),
    null, "", 0, Instant.EPOCH, "1".repeat(32), Instant.EPOCH, null,
    notificationEnabled = true
)
