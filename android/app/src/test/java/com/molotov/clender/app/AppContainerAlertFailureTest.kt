package com.molotov.clender.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import com.molotov.clender.app.alert.AlertScheduleStatus
import com.molotov.clender.core.model.EventType
import com.molotov.clender.domain.event.AddEventCommand
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [26, 36],
    application = ClenderApplication::class,
    shadows = [RejectingAlertChannels::class]
)
class AppContainerAlertFailureTest {
    @Test
    fun rejectedChannelCreationCannotTurnAPersistedScheduleIntoAFailedWrite() = runBlocking {
        val application = RuntimeEnvironment.getApplication() as ClenderApplication
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val container = AppContainer(application)
        try {
            val saved = container.eventService.add(
                AddEventCommand(
                    EventType.REMINDER,
                    "Future reminder",
                    LocalDateTime.now().plusDays(1).withSecond(0).withNano(0),
                    notificationEnabled = true
                )
            )
            assertEquals(saved, container.eventRepository.findById(saved.id))
            assertEquals(1L, container.scheduleMutationVersion.value)
            container.alertRuntime.refresh().join()
            assertEquals(
                AlertScheduleStatus.CHANNEL_BLOCKED,
                container.alertRuntime.status.value[saved.id]
            )
        } finally {
            container.close()
            assertTrue(application.deleteDatabase(AppContainer.DATABASE_NAME))
        }
    }
}

@Implements(NotificationManager::class)
class RejectingAlertChannels : ShadowNotificationManager() {
    @Implementation
    override fun createNotificationChannel(channel: NotificationChannel): Unit =
        throw SecurityException("Test channel creation rejection")
}
