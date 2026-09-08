package com.molotov.clender.ui.event

import android.os.Build
import android.provider.Settings
import com.molotov.clender.app.alert.AlertPlanFactory
import com.molotov.clender.core.model.eventFixture
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AlertSettingsAndTimerPolicyTest {
    @Test
    fun backgroundSettingsOpenTheListAndOtherSettingsTargetOnlyThisPackage() {
        val packageName = "com.molotov.clender"
        val battery = alertSettingsIntent(AlertSettingsTarget.BATTERY, packageName)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, battery.action)
        assertNull(battery.data)
        val details = alertSettingsIntent(AlertSettingsTarget.APP, packageName)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, details.action)
        assertEquals("package:$packageName", details.dataString)
        val exact = alertSettingsIntent(AlertSettingsTarget.EXACT, packageName)
        assertEquals("package:$packageName", exact.dataString)
        assertEquals(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
            } else {
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            },
            exact.action
        )
        val notification = alertSettingsIntent(AlertSettingsTarget.NOTIFICATIONS, packageName)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notification.action)
        assertEquals(packageName, notification.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun timerUsesElapsedMinutesAcrossDstAndRemainingTimeRoundsUp() {
        val zone = ZoneId.of("America/New_York")
        val event = eventFixture(
            startTime = LocalDateTime.of(2026, 3, 8, 1, 30)
        ).copy(timerMinutes = 120)
        val deadline = requireNotNull(AlertPlanFactory.timerDeadline(event, zone))
        assertEquals(LocalDateTime.of(2026, 3, 8, 4, 30), deadline.atZone(zone).toLocalDateTime())
        assertEquals(0L, timerRemainingMinutes(deadline, deadline))
        assertEquals(0L, timerRemainingMinutes(deadline, deadline.plusSeconds(1)))
        assertEquals(1L, timerRemainingMinutes(deadline, deadline.minusNanos(1)))
        assertEquals(1L, timerRemainingMinutes(deadline, deadline.minusSeconds(60)))
        assertEquals(2L, timerRemainingMinutes(deadline, deadline.minusSeconds(60).minusNanos(1)))
        assertEquals(120L, timerRemainingMinutes(deadline, Instant.parse("2026-03-08T06:30:00Z")))
    }
}
