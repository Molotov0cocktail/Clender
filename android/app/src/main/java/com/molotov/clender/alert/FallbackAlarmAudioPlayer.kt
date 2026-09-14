package com.molotov.clender.alert

import android.media.RingtoneManager
import android.net.Uri
import java.io.IOException

/** An unreadable custom channel sound may use the system alarm sound once, before playback. */
internal class FallbackAlarmAudioPlayer(private val playerFactory: () -> AlarmAudioPlayer) :
    AlarmAudioPlayer {
    private var player: AlarmAudioPlayer? = null
    private var fallback: Uri? = null
    private var generation = 0
    private var prepared = false
    private var onPrepared: (() -> Unit)? = null
    private var onFailed: (() -> Unit)? = null

    override fun prepare(sound: Uri, prepared: () -> Unit, failed: () -> Unit) {
        check(player == null && onPrepared == null)
        onPrepared = prepared
        onFailed = failed
        fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?.takeUnless { it == sound }
        prepareAttempt(sound)
    }

    private fun prepareAttempt(sound: Uri) {
        val attempt = ++generation
        val owned = try {
            playerFactory()
        } catch (_: RuntimeException) {
            onFailed?.invoke()
            return
        }
        player = owned
        try {
            owned.prepare(
                sound,
                prepared = {
                    if (attempt == generation) {
                        prepared = true
                        onPrepared?.invoke()
                    }
                },
                failed = { if (attempt == generation) preparationFailed() }
            )
        } catch (_: SecurityException) {
            if (attempt == generation) preparationFailed()
        } catch (_: IOException) {
            if (attempt == generation) preparationFailed()
        } catch (_: RuntimeException) {
            if (attempt == generation) onFailed?.invoke()
        }
    }

    private fun preparationFailed() {
        if (prepared) {
            // Playback or focus failures must never restart a sounding alarm automatically.
            onFailed?.invoke()
        } else {
            val next = fallback
            fallback = null
            releasePlayer()
            if (next == null) onFailed?.invoke() else prepareAttempt(next)
        }
    }

    override fun start() {
        check(prepared)
        checkNotNull(player).start()
    }

    override fun release() {
        onPrepared = null
        onFailed = null
        fallback = null
        prepared = false
        releasePlayer()
    }

    private fun releasePlayer() {
        val owned = player
        player = null
        generation++
        try {
            owned?.release()
        } catch (_: RuntimeException) {
            // Detach first so a failed source cannot emit a late callback into its replacement.
        }
    }
}
