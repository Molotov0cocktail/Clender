package com.molotov.clender.ui.settings

import android.content.ActivityNotFoundException
import com.molotov.clender.data.settings.AlarmSoundStore
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class AlarmSoundControllerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun unavailablePickerReportsFailureAndPreservesOldSelection() = runTest {
        val store = AlarmSoundStore(temporary.newFolder()) { }
        store.importSound(open = { ByteArrayInputStream(byteArrayOf(1)) })
        val controller = AlarmSoundController(store, this, StandardTestDispatcher(testScheduler))
        controller.enter()
        advanceUntilIdle()
        val failures = listOf(
            ActivityNotFoundException("synthetic"),
            SecurityException("synthetic")
        )
        for (failure in failures) {
            controller.launchSelection { _, types ->
                assertTrue(types.contentEquals(arrayOf("audio/*")))
                throw failure
            }
            assertFalse(controller.busy)
            assertTrue(controller.failed)
            assertTrue(controller.selected)
            assertTrue(
                requireNotNull(store.selectedFile()).readBytes().contentEquals(byteArrayOf(1))
            )
        }
        controller.launchSelection { ticket, _ -> controller.choose(ticket, null) }
        assertFalse(controller.failed)
        assertFalse(controller.busy)
    }

    @Test
    fun pickerCancelAndBusyNeverReplaceExistingSelection() = runTest {
        val store = AlarmSoundStore(temporary.newFolder()) { }
        store.importSound(open = { ByteArrayInputStream(byteArrayOf(1)) })
        val controller = AlarmSoundController(store, this, StandardTestDispatcher(testScheduler))
        controller.enter()
        advanceUntilIdle()
        assertTrue(controller.selected)
        val ticket = requireNotNull(controller.beginSelection())
        assertTrue(controller.busy)
        assertNull(controller.beginSelection())
        controller.choose(ticket, null)
        assertFalse(controller.busy)
        assertTrue(controller.selected)
        assertTrue(requireNotNull(store.selectedFile()).readBytes().contentEquals(byteArrayOf(1)))
    }

    @Test
    fun importFailureKeepsOldSelectionAndRestoreClearsIt() = runTest {
        val store = AlarmSoundStore(temporary.newFolder()) { }
        store.importSound(open = { ByteArrayInputStream(byteArrayOf(1)) })
        val controller = AlarmSoundController(store, this, StandardTestDispatcher(testScheduler))
        controller.enter()
        advanceUntilIdle()
        controller.choose(requireNotNull(controller.beginSelection())) {
            throw IOException("synthetic")
        }
        advanceUntilIdle()
        assertTrue(controller.failed)
        assertTrue(controller.selected)
        controller.remove()
        advanceUntilIdle()
        assertFalse(controller.failed)
        assertFalse(controller.selected)
        assertNull(store.selectedFile())
    }

    @Test
    fun leavingCancelsQueuedImportAndRejectsLatePickerResult() = runTest {
        val store = AlarmSoundStore(temporary.newFolder()) { }
        val controller = AlarmSoundController(store, this, StandardTestDispatcher(testScheduler))
        controller.enter()
        advanceUntilIdle()
        val stale = requireNotNull(controller.beginSelection())
        controller.choose(stale) { ByteArrayInputStream(byteArrayOf(1)) }
        controller.leave()
        controller.enter()
        advanceUntilIdle()
        controller.choose(stale) { ByteArrayInputStream(byteArrayOf(2)) }
        assertNull(store.selectedFile())
        controller.choose(requireNotNull(controller.beginSelection())) {
            ByteArrayInputStream(byteArrayOf(3))
        }
        advanceUntilIdle()
        assertTrue(controller.selected)
        assertFalse(controller.busy)
        assertTrue(requireNotNull(store.selectedFile()).readBytes().contentEquals(byteArrayOf(3)))
    }
}
