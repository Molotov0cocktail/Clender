package com.molotov.clender.alert

import android.app.Application
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = Application::class)
class ImportantAlarmNotificationTest {
    @Test
    fun foregroundSummaryDoesNotMutateEventTitleCategoryOrStopAction() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val original = Notification.Builder(context, "clender_alarm_v1")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Synthetic important event")
            .setCategory(Notification.CATEGORY_ALARM)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_delete,
                    "Stop alert",
                    null
                ).build()
            )
            .build()
        val summary = importantAlarmSummary(context, original)
        assertEquals(
            "Synthetic important event",
            original.extras.getCharSequence(Notification.EXTRA_TITLE)
        )
        assertEquals(Notification.CATEGORY_ALARM, original.category)
        assertEquals(1, original.actions.size)
        assertEquals("Stop alert", original.actions.single().title)
        assertFalse(original.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.CATEGORY_SERVICE, summary.category)
        assertTrue(summary.actions.isNullOrEmpty())
        assertEquals("clender_important_alarms", summary.group)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, summary.groupAlertBehavior)
    }
}
