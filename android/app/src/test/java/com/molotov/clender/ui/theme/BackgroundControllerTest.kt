package com.molotov.clender.ui.theme

import com.molotov.clender.data.settings.BackgroundStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class BackgroundControllerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun busyPreventsOverlappingWritesAndEndsOnSuccess() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller =
            BackgroundController(BackgroundStore(temporary.newFolder()), this, dispatcher)
        controller.setStrength(100)
        assertTrue(controller.busy)
        controller.setStrength(0)
        advanceUntilIdle()
        assertEquals(100, controller.selection.strength)
        assertFalse(controller.busy)
        assertFalse(controller.failed)
    }

    @Test
    fun writeFailureRetainsPreviousStateAndAllowsRetry() = runTest {
        val root = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = BackgroundController(BackgroundStore(root), this, dispatcher)
        val config = File(root, "settings.json").apply { mkdir() }
        File(config, "block").writeText("fixture")
        controller.setStrength(100)
        advanceUntilIdle()
        assertTrue(controller.failed)
        assertFalse(controller.busy)
        assertEquals(35, controller.selection.strength)
    }

    @Test
    fun lifecycleCancellationReleasesBusyWithoutReportingAnError() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lifecycle = SupervisorJob()
        val scope = CoroutineScope(lifecycle + dispatcher)
        val controller = BackgroundController(
            BackgroundStore(temporary.newFolder()),
            scope,
            StandardTestDispatcher(testScheduler, "io")
        )
        controller.load()
        assertTrue(controller.busy)
        lifecycle.cancel()
        advanceUntilIdle()
        assertFalse(controller.busy)
        assertFalse(controller.failed)
        assertTrue(lifecycle.isCancelled)
    }
}
