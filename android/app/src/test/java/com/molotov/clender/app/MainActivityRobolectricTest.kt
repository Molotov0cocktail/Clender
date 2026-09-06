package com.molotov.clender.app

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.molotov.clender.testsupport.ProductionActivityTestResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
class MainActivityRobolectricTest {
    @Test
    fun activityReachesResumedWithoutFinishing() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var application: ClenderApplication
        try {
            scenario.onActivity { activity ->
                application = activity.application as ClenderApplication
                assertFalse(activity.isFinishing)
                assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            }
        } finally {
            ProductionActivityTestResources.close(application, scenario)
        }
    }
}
