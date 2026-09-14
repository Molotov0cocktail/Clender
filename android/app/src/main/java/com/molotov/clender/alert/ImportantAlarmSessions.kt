package com.molotov.clender.alert

import android.app.Notification
import android.net.Uri
import com.molotov.clender.app.alert.AlertToken
import java.io.IOException
import java.util.concurrent.CompletableFuture

internal interface AlarmAudioPlayer {
    fun prepare(sound: Uri, prepared: () -> Unit, failed: () -> Unit)
    fun start()
    fun release()
}

internal class AlarmPlaybackRequest(
    val token: AlertToken,
    val notification: Notification,
    val sound: Uri
) {
    val result = CompletableFuture<Boolean>()

    @Synchronized
    fun acknowledge(start: () -> Unit): Boolean {
        if (result.isDone) return result.getNow(false)
        return try {
            start()
            result.complete(true)
            true
        } catch (_: RuntimeException) {
            result.complete(false)
            false
        }
    }

    @Synchronized
    fun expire(): Boolean {
        result.complete(false)
        return result.getNow(false)
    }

    fun isRejected(): Boolean = result.isDone && !result.getNow(false)
}

/** Main-thread owned sessions share the user's alarm-channel sound without stopping each other. */
internal class ImportantAlarmSessions(
    private val playerFactory: () -> AlarmAudioPlayer,
    private val foreground: (AlarmPlaybackRequest) -> Unit,
    private val idle: () -> Unit,
    private val failure: (AlertToken) -> Unit
) {
    private val requests = linkedMapOf<AlertToken, AlarmPlaybackRequest>()
    private var player: AlarmAudioPlayer? = null
    private var playing = false

    fun add(request: AlarmPlaybackRequest) {
        if (request.isRejected() || requests.size >= MAX_ACTIVE_ALARMS) {
            request.expire()
            if (requests.isEmpty()) idle()
            return
        }
        try {
            foreground(requests.values.firstOrNull() ?: request)
            requests.put(request.token, request)?.expire()
            if (playing) {
                request.acknowledge { }
            } else if (player == null) {
                val owned = FallbackAlarmAudioPlayer(playerFactory)
                player = owned
                owned.prepare(request.sound, ::prepared, ::failed)
            }
        } catch (_: RuntimeException) {
            reject(request)
        } catch (_: IOException) {
            reject(request)
        }
    }

    private fun reject(request: AlarmPlaybackRequest) {
        request.expire()
        if (requests[request.token] === request) {
            failed()
        } else if (requests.isEmpty()) {
            idle()
        }
    }

    fun stop(eventId: Long) {
        requests.values.filter { it.token.eventId == eventId }.forEach {
            requests.remove(it.token)
            it.expire()
        }
        finishOrRefresh()
    }

    fun cancel(request: AlarmPlaybackRequest) {
        if (requests[request.token] === request && request.isRejected()) {
            requests.remove(request.token)
            finishOrRefresh()
        }
    }

    fun close() = failed()

    fun silence() {
        requests.values.forEach { it.expire() }
        requests.clear()
        release()
        idle()
    }

    val isEmpty: Boolean get() = requests.isEmpty()

    private fun prepared() {
        val readyPlayer = player ?: return
        val pending = requests.values.toList()
        for (request in pending) {
            if (request.acknowledge { if (!playing) readyPlayer.start() }) {
                playing = true
            } else {
                requests.remove(request.token)
            }
        }
        finishOrRefresh()
    }

    private fun failed() {
        val active = requests.values.toList()
        requests.clear()
        active.forEach { request ->
            if (request.result.getNow(false)) failure(request.token)
            request.expire()
        }
        release()
        if (active.isNotEmpty()) idle()
    }

    private fun finishOrRefresh() {
        if (requests.isEmpty()) {
            release()
            idle()
        } else {
            try {
                foreground(requests.values.first())
            } catch (_: RuntimeException) {
                failed()
            }
        }
    }

    private fun release() {
        val owned = player
        player = null
        playing = false
        try {
            owned?.release()
        } catch (_: RuntimeException) {
            // The session is detached even if a platform player fails during release.
        }
    }

    private companion object {
        const val MAX_ACTIVE_ALARMS = 32
    }
}
