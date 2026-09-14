package com.molotov.clender.alert

import android.app.Application
import android.app.Notification
import android.net.Uri
import com.molotov.clender.app.alert.AlertKind
import com.molotov.clender.app.alert.AlertToken
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = Application::class)
class ImportantAlarmSessionsTest {
    @Test
    fun readableCustomSoundPlaysWithoutFallbackAndFocusFailureNeverRetries() {
        listOf(false, true).forEach { focusFails ->
            val harness = Harness()
            val custom = Uri.parse("content://synthetic/readable")
            harness.player.startFails = focusFails
            val request = request(1, custom)
            harness.sessions.add(request)
            harness.player.ready()
            assertEquals(listOf(custom), harness.player.preparedSounds)
            assertEquals(!focusFails, request.result.get())
            if (!focusFails) harness.sessions.stop(1)
            assertEquals(1, harness.player.releases)
        }
    }

    @Test
    fun inaccessibleCustomSoundFallsBackOnceAndOnlyAcknowledgesStartedDefaultSound() {
        listOf(IOException("Synthetic missing sound"), SecurityException("Synthetic denied sound"))
            .forEach { error ->
                val harness = Harness()
                val custom = Uri.parse("content://synthetic/sound")
                harness.player.unreadable = custom
                harness.player.prepareError = error
                val request = request(1, custom)
                harness.sessions.add(request)
                assertEquals(listOf(custom, defaultSound()), harness.player.preparedSounds)
                assertFalse(request.result.isDone)
                assertEquals(1, harness.player.releases)
                harness.player.ready()
                assertTrue(request.result.get())
                assertEquals(1, harness.player.starts)
                harness.sessions.close()
                assertEquals(2, harness.player.releases)
            }
    }

    @Test
    fun asynchronousCustomPreparationFailureUsesDefaultAndIgnoresObsoleteCallbacks() {
        val harness = Harness()
        val custom = Uri.parse("content://synthetic/sound")
        val request = request(1, custom)
        harness.sessions.add(request)
        val obsoleteReady = harness.player.ready
        val obsoleteFailure = harness.player.fail
        obsoleteFailure()
        assertEquals(listOf(custom, defaultSound()), harness.player.preparedSounds)
        obsoleteReady()
        obsoleteFailure()
        assertFalse(request.result.isDone)
        assertEquals(0, harness.player.starts)
        harness.player.ready()
        assertTrue(request.result.get())
        assertEquals(1, harness.player.starts)
        harness.sessions.stop(1)
    }

    @Test
    fun defaultFailureAfterCustomFailureStopsWithoutFurtherRetriesOrSuccess() {
        val harness = Harness()
        harness.player.prepareFails = true
        val custom = Uri.parse("content://synthetic/sound")
        val request = request(1, custom)
        harness.sessions.add(request)
        assertEquals(listOf(custom, defaultSound()), harness.player.preparedSounds)
        assertFalse(request.result.get())
        assertEquals(0, harness.player.starts)
        assertEquals(2, harness.player.releases)
        assertEquals(1, harness.idleCount)
    }

    @Test
    fun stoppingDuringFallbackPreparationPreventsLateDefaultPlayback() {
        val harness = Harness()
        val custom = Uri.parse("content://synthetic/sound")
        harness.player.unreadable = custom
        val request = request(1, custom)
        harness.sessions.add(request)
        assertEquals(listOf(custom, defaultSound()), harness.player.preparedSounds)
        harness.sessions.stop(1)
        harness.player.ready()
        assertFalse(request.result.get())
        assertEquals(0, harness.player.starts)
        assertEquals(2, harness.player.releases)
    }

    @Test
    fun unreadableSelectedSoundRejectsRequestAndReleasesWithoutCrashingService() {
        val harness = Harness()
        harness.player.prepareFails = true
        val request = request(1)
        harness.sessions.add(request)
        assertFalse(request.result.get())
        assertEquals(1, harness.player.releases)
        assertEquals(1, harness.idleCount)
    }

    @Test
    fun foregroundFailureRejectsFirstRequestAndStopsUnpromotedService() {
        val harness = Harness()
        harness.foregroundFails = true
        val request = request(1)
        harness.sessions.add(request)
        assertFalse(request.result.get())
        assertEquals(1, harness.idleCount)
        assertEquals(0, harness.player.starts)
    }

    @Test
    fun failedPromotionForAnotherRequestDoesNotDiscardExistingAlarm() {
        val harness = Harness()
        val first = request(1)
        harness.sessions.add(first)
        harness.player.ready()
        harness.foregroundFails = true
        val second = request(2)
        harness.sessions.add(second)
        assertFalse(second.result.get())
        assertEquals(0, harness.player.releases)
        assertEquals(0, harness.idleCount)
        assertTrue(harness.failures.isEmpty())
        harness.sessions.stop(1)
    }

    @Test
    fun foregroundPrecedesPreparationAndOnlyStartedAudioAcknowledgesSuccess() {
        val harness = Harness()
        val request = request(1)
        harness.sessions.add(request)
        assertEquals(listOf("foreground", "prepare"), harness.steps)
        assertFalse(request.result.isDone)
        harness.player.ready()
        assertTrue(request.result.get())
        assertEquals(1, harness.player.starts)
        harness.sessions.close()
        assertEquals(1, harness.player.releases)
    }

    @Test
    fun timedOutRequestCannotPlayWhenPreparationEventuallyCompletes() {
        val harness = Harness()
        val request = request(1)
        harness.sessions.add(request)
        request.expire()
        harness.player.ready()
        assertFalse(request.result.get())
        assertEquals(0, harness.player.starts)
        assertEquals(1, harness.player.releases)
        assertEquals(1, harness.idleCount)
    }

    @Test
    fun stoppingOneEventKeepsOtherAlarmPlayingAndFinalStopReleases() {
        val harness = Harness()
        val first = request(1)
        harness.sessions.add(first)
        harness.player.ready()
        val second = request(2)
        harness.sessions.add(second)
        assertTrue(second.result.get())
        harness.sessions.stop(1)
        assertEquals(0, harness.player.releases)
        assertEquals(0, harness.idleCount)
        harness.sessions.stop(2)
        assertEquals(1, harness.player.releases)
        assertEquals(1, harness.idleCount)
        assertTrue(harness.failures.isEmpty())
    }

    @Test
    fun preparationFailureDoesNotAcknowledgeSuccessOrLeaveAnIdleService() {
        val harness = Harness()
        val request = request(1)
        harness.sessions.add(request)
        harness.player.fail()
        assertFalse(request.result.get())
        assertEquals(1, harness.player.releases)
        assertEquals(1, harness.idleCount)
    }

    @Test
    fun errorAfterAcknowledgementReportsFailureForEveryActiveToken() {
        val harness = Harness()
        val first = request(1)
        harness.sessions.add(first)
        harness.player.ready()
        val second = request(2)
        harness.sessions.add(second)
        harness.player.fail()
        assertEquals(setOf(first.token, second.token), harness.failures.toSet())
        assertEquals(1, harness.player.releases)
        harness.sessions.close()
        assertEquals(1, harness.player.releases)
    }

    private fun defaultSound(): Uri = Uri.parse("content://settings/system/alarm_alert")

    private fun request(id: Long, sound: Uri = defaultSound()) = AlarmPlaybackRequest(
        token = AlertToken(id, AlertKind.ALARM, 1, "2026-09-08T00:00:00Z"),
        notification = Notification(),
        sound = sound
    )

    private class Harness {
        val steps = mutableListOf<String>()
        val player = FakePlayer(steps)
        val failures = mutableListOf<AlertToken>()
        var idleCount = 0
        var foregroundFails = false
        val sessions = ImportantAlarmSessions(
            playerFactory = { player },
            foreground = {
                check(!foregroundFails)
                steps += "foreground"
            },
            idle = { idleCount++ },
            failure = { failures += it }
        )
    }

    private class FakePlayer(private val steps: MutableList<String>) : AlarmAudioPlayer {
        var starts = 0
        var releases = 0
        var prepareFails = false
        var startFails = false
        var unreadable: Uri? = null
        var prepareError: Exception = IOException("Synthetic unreadable alarm URI")
        val preparedSounds = mutableListOf<Uri>()
        lateinit var ready: () -> Unit
        lateinit var fail: () -> Unit

        override fun prepare(sound: Uri, prepared: () -> Unit, failed: () -> Unit) {
            preparedSounds += sound
            if (sound == unreadable) throw prepareError
            if (prepareFails) throw IOException("Synthetic unreadable alarm URI")
            steps += "prepare"
            ready = prepared
            fail = failed
        }

        override fun start() {
            check(!startFails)
            starts++
        }

        override fun release() {
            releases++
        }
    }
}
