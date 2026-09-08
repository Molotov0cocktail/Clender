package com.molotov.clender.alert

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.app.alert.AlertKind
import com.molotov.clender.app.alert.AlertPlanFactory
import com.molotov.clender.app.alert.AlertReceipt
import com.molotov.clender.app.alert.AlertSchedule
import com.molotov.clender.app.alert.AlertToken
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = Application::class)
class AlertPlatformContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [26])
    fun alarmStartFailurePostsNothingAndDismissStopsPlayback() {
        val delivery = FakeAlarmDelivery().apply { succeeds = false }
        val platform = PlatformEventAlerts(context, delivery)
        val event = event().copy(alarmEnabled = true)
        val token = AlertPlanFactory.tokens(event, ZoneOffset.UTC).single()
        assertFalse(platform.show(event, token))
        assertTrue(
            shadowOf(context.getSystemService(NotificationManager::class.java))
                .allNotifications.isEmpty()
        )
        assertEquals(listOf(token), delivery.started)
        platform.dismiss(event.id)
        assertEquals(listOf(event.id), delivery.stopped)
    }

    @Test
    fun immutableActionsHaveDistinctCanonicalIdentitiesAndRejectTampering() {
        val token = token()
        val intent = AlertPendingIntents.intent(context, token)
        assertEquals(
            AlertBroadcastAction(token, false),
            AlertPendingIntents.validate(context, intent)
        )
        assertNotEquals(
            AlertPendingIntents.broadcast(context, token),
            AlertPendingIntents.broadcast(context, token, stop = true)
        )
        assertNotEquals(
            AlertPendingIntents.broadcast(context, token),
            AlertPendingIntents.broadcast(
                context,
                token.copy(
                    triggerAtMillis =
                        token.triggerAtMillis + 1
                )
            )
        )
        val flags = shadowOf(AlertPendingIntents.broadcast(context, token)).flags
        assertTrue(flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
        listOf(
            Intent(intent).setPackage("other.application"),
            Intent(intent).setAction("other.action"),
            Intent(intent).putExtra("event_id", 5),
            Intent(intent).apply { component = null },
            Intent(intent).apply {
                data =
                    data?.buildUpon()?.appendQueryParameter("other", "value")?.build()
            }
        ).forEach { assertNull(AlertPendingIntents.validate(context, it)) }
        val stop = AlertPendingIntents.intent(context, token, stop = true)
        assertEquals(AlertBroadcastAction(token, true), AlertPendingIntents.validate(context, stop))
    }

    @Test
    fun privateLedgerPersistsClaimsDeliveryAndPendingPrecisionAcrossInstances() {
        val token = token()
        val first = SharedPreferencesAlertLedger(context)
        first.savePending(setOf(AlertSchedule(token, true)))
        assertTrue(first.claim(token))
        val second = SharedPreferencesAlertLedger(context)
        assertEquals(setOf(AlertSchedule(token, true)), second.pending())
        assertEquals(AlertReceipt.CLAIMED, second.receipt(token))
        assertFalse(second.claim(token))
        second.delivered(token)
        assertEquals(AlertReceipt.DELIVERED, first.receipt(token))
        second.failed(token)
        assertEquals(AlertReceipt.FAILED, first.receipt(token))
        assertFalse(first.claim(token))
        second.release(token)
        assertNull(first.receipt(token))
        assertTrue(first.claim(token))
        first.release(token)
        first.savePending(emptySet())
    }

    @Test
    fun systemRestoreShapesAllowBootTimeZoneAndGrantButRejectArbitraryInputs() {
        listOf(
            Intent(Intent.ACTION_BOOT_COMPLETED),
            Intent(Intent.ACTION_TIME_CHANGED),
            Intent(Intent.ACTION_TIMEZONE_CHANGED).putExtra("time-zone", "Asia/Shanghai"),
            Intent("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        ).forEach { assertTrue(validRestoreIntent(it)) }
        listOf(
            Intent("arbitrary"),
            Intent(Intent.ACTION_TIME_CHANGED).putExtra("extra", "value"),
            Intent(Intent.ACTION_TIMEZONE_CHANGED).putExtra("time-zone", "invalid/zone"),
            Intent(Intent.ACTION_BOOT_COMPLETED).putExtra("android.intent.extra.user_handle", -1)
        ).forEach { assertFalse(validRestoreIntent(it)) }
    }

    @Test
    fun channelsSeparateOrdinaryTimerAndAlarmSoundAndPreserveBlockedSettings() {
        val platform = PlatformEventAlerts(context, FakeAlarmDelivery())
        val manager = context.getSystemService(NotificationManager::class.java)
        val alarm = requireNotNull(manager.getNotificationChannel("clender_alarm_v1"))
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarm.sound)
        assertEquals(AudioAttributes.USAGE_ALARM, alarm.audioAttributes.usage)
        assertNotNull(manager.getNotificationChannel("clender_notification_v1"))
        assertNotNull(manager.getNotificationChannel("clender_timer_v1"))
        assertTrue(platform.permissions().alarmChannelAllowed)
    }

    @Test
    @Config(sdk = [26])
    fun importantForegroundNotificationMustNotRequestSystemChannelSound() {
        val platform = PlatformEventAlerts(context, FakeAlarmDelivery())
        val event = event().copy(alarmEnabled = true)
        val token = AlertPlanFactory.tokens(event, ZoneOffset.UTC).single()
        assertTrue(platform.show(event, token))
        val notification = shadowOf(
            context.getSystemService(NotificationManager::class.java)
        ).allNotifications.single()
        assertEquals("clender_important_alarms", notification.group)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
        assertEquals(0, notification.flags and Notification.FLAG_INSISTENT)
        assertNotNull(notification.actions.single().actionIntent)
    }

    @Test
    @Config(sdk = [26])
    fun importantAlarmUsesAlarmClockAndSilentNotificationWithAStopAction() {
        val platform = PlatformEventAlerts(context, FakeAlarmDelivery())
        val event = event().copy(alarmEnabled = true)
        val token = AlertPlanFactory.tokens(event, ZoneOffset.UTC).single()
        platform.schedule(AlertSchedule(token, true))
        val alarms = context.getSystemService(AlarmManager::class.java)
        assertEquals(token.triggerAtMillis, requireNotNull(alarms.nextAlarmClock).triggerTime)
        assertTrue(platform.show(event, token))
        val notifications = shadowOf(
            context.getSystemService(NotificationManager::class.java)
        ).allNotifications
        assertEquals(1, notifications.size)
        val notification = notifications.single()
        assertEquals(0, notification.flags and Notification.FLAG_INSISTENT)
        assertNotNull(notification.actions.single().actionIntent)
        platform.dismiss(event.id)
        assertTrue(
            shadowOf(
                context.getSystemService(NotificationManager::class.java)
            ).allNotifications.isEmpty()
        )
        platform.cancel(token)
        assertNull(alarms.nextAlarmClock)
    }

    private fun token(): AlertToken = AlertPlanFactory.tokens(event(), ZoneOffset.UTC).single()

    private fun event() = Event(
        1, EventType.REMINDER, "Synthetic alert", LocalDateTime.of(2026, 9, 8, 10, 0),
        null, "", 0, Instant.EPOCH, "1".repeat(32), Instant.EPOCH, null,
        notificationEnabled = true
    )
}

private class FakeAlarmDelivery : ImportantAlarmDelivery {
    var succeeds = true
    val started = mutableListOf<AlertToken>()
    val stopped = mutableListOf<Long>()
    override val failures = emptyFlow<AlertToken>()
    override fun start(
        event: Event,
        token: AlertToken,
        notification: Notification,
        sound: Uri?
    ): Boolean {
        started += token
        return succeeds
    }
    override fun stop(eventId: Long) {
        stopped += eventId
    }
}
