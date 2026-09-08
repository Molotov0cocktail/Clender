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

    private fun request(id: Long) = AlarmPlaybackRequest(
        token = AlertToken(id, AlertKind.ALARM, 1, "2026-09-08T00:00:00Z"),
        notification = Notification(),
        sound = Uri.parse("content://settings/system/alarm_alert")
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
        lateinit var ready: () -> Unit
        lateinit var fail: () -> Unit

        override fun prepare(sound: Uri, prepared: () -> Unit, failed: () -> Unit) {
            if (prepareFails) throw IOException("Synthetic unreadable alarm URI")
            steps += "prepare"
            ready = prepared
            fail = failed
        }

        override fun start() {
            starts++
        }

        override fun release() {
            releases++
        }
    }
}
