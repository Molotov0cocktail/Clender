package com.molotov.clender.alert

import android.app.Application
import android.app.Service
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = Application::class)
class ImportantAlarmServiceContractTest {
    @Test
    fun missingTicketDoesNotStartAnIdleOrStickyAlarmService() {
        val controller = Robolectric.buildService(serviceClass()).create()
        val service = controller.get()
        try {
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun arbitraryIntentCannotStartAlarmPlayback() {
        val controller = Robolectric.buildService(serviceClass()).create()
        val service = controller.get()
        try {
            val arbitrary = Intent("com.molotov.clender.PLAY_ALARM")
                .putExtra("event_id", 1L)
                .putExtra("ticket", "unregistered-ticket")
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(arbitrary, 0, 1))
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    private fun serviceClass(): Class<out Service> =
        Class.forName("com.molotov.clender.alert.ImportantAlarmService")
            .asSubclass(Service::class.java)
}
