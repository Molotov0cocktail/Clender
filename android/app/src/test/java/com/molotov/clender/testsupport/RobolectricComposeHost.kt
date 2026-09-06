package com.molotov.clender.testsupport

import android.os.Looper
import androidx.activity.ComponentActivity
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController

internal class RobolectricComposeHost {
    private lateinit var controller: ActivityController<ComponentActivity>

    lateinit var activity: ComponentActivity
        private set

    fun start() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity = controller.get()
    }

    fun close() {
        try {
            if (::controller.isInitialized) controller.pause().stop().destroy()
        } finally {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
