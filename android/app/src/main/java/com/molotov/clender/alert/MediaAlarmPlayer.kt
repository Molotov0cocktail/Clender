package com.molotov.clender.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.PowerManager

internal class MediaAlarmPlayer(private val context: Context) : AlarmAudioPlayer {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener(::focusChanged)
        .build()
    private var player: MediaPlayer? = null
    private var failed: (() -> Unit)? = null
    private var pausedForFocus = false

    override fun prepare(sound: Uri, prepared: () -> Unit, failed: () -> Unit) {
        this.failed = failed
        val owned = MediaPlayer()
        player = owned
        owned.setAudioAttributes(attributes)
        owned.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        owned.isLooping = true
        owned.setOnPreparedListener { if (player === owned) prepared() }
        owned.setOnErrorListener { _, _, _ ->
            if (player === owned) this.failed?.invoke()
            true
        }
        owned.setDataSource(context, sound)
        owned.prepareAsync()
    }

    override fun start() {
        check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        checkNotNull(player).start()
    }

    override fun release() {
        val owned = player
        player = null
        failed = null
        pausedForFocus = false
        try {
            owned?.release()
        } finally {
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private fun focusChanged(change: Int) {
        val owned = player ?: return
        try {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> failed?.invoke()

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (owned.isPlaying) {
                        owned.pause()
                        pausedForFocus = true
                    }
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    owned.setVolume(DUCK_VOLUME, DUCK_VOLUME)

                AudioManager.AUDIOFOCUS_GAIN -> {
                    owned.setVolume(1f, 1f)
                    if (pausedForFocus) {
                        pausedForFocus = false
                        owned.start()
                    }
                }
            }
        } catch (_: RuntimeException) {
            failed?.invoke()
        }
    }

    private companion object {
        const val DUCK_VOLUME = 0.2f
    }
}
