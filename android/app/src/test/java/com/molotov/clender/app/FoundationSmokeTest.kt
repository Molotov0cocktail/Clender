package com.molotov.clender.app

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
class FoundationSmokeTest {
    @Test
    fun applicationStartsWithoutCreatingBusinessState() {
        val application: Application = RuntimeEnvironment.getApplication()

        assertEquals(ClenderApplication::class.java, application::class.java)
        assertTrue(application.packageName.startsWith("com.molotov.clender"))
        assertTrue(application.databaseList().isEmpty())
        assertTrue(application.getSharedPreferences("foundation_probe", 0).all.isEmpty())
    }
}
