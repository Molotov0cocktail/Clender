package com.molotov.clender.alert

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import com.molotov.clender.app.alert.AlertKind
import com.molotov.clender.app.alert.AlertToken
import com.molotov.clender.core.model.Event
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

object ImportantAlarmPlayback {
    private const val ACTION = "com.molotov.clender.action.IMPORTANT_ALARM_PLAYBACK"
    private const val MAX_TICKETS = 32
    private const val START_TIMEOUT_SECONDS = 3L
    private val tickets = mutableMapOf<String, AlarmPlaybackRequest>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessions: ImportantAlarmSessions? = null
    private val mutableFailures = MutableSharedFlow<AlertToken>(
        replay = MAX_TICKETS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val failures: SharedFlow<AlertToken> = mutableFailures

    fun start(
        context: Context,
        event: Event,
        token: AlertToken,
        notification: Notification,
        soundUri: Uri?
    ): Boolean {
        val invalid = token.kind != AlertKind.ALARM || token.eventId != event.id
        val mainThreadPlayback = soundUri != null && Looper.myLooper() == Looper.getMainLooper()
        if (invalid || mainThreadPlayback) return false
        return if (soundUri == null) {
            true
        } else {
            launch(context, AlarmPlaybackRequest(token, notification, soundUri))
        }
    }

    private fun launch(context: Context, request: AlarmPlaybackRequest): Boolean {
        val ticket = UUID.randomUUID().toString()
        val registered = synchronized(tickets) {
            if (tickets.size >= MAX_TICKETS) {
                false
            } else {
                tickets[ticket] = request
                true
            }
        }
        if (!registered) return false
        return try {
            context.applicationContext.startForegroundService(
                Intent(context, ImportantAlarmService::class.java)
                    .setAction(ACTION)
                    .setData(ticketUri(ticket))
            )
            request.result.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            request.expire()
        } catch (_: Exception) {
            request.expire()
        } finally {
            synchronized(tickets) { tickets.remove(ticket) }
            if (request.isRejected()) mainHandler.post { sessions?.cancel(request) }
        }
    }

    fun stop(eventId: Long) {
        synchronized(tickets) {
            tickets.values.filter { it.token.eventId == eventId }.forEach { it.expire() }
        }
        mainHandler.post { sessions?.stop(eventId) }
    }

    internal fun take(context: Context, intent: Intent?): AlarmPlaybackRequest? {
        val ticket = intent?.data?.lastPathSegment
        val trusted = intent != null && ticket != null && trustedShape(context, intent) &&
            intent.data == ticketUri(ticket)
        return if (trusted) synchronized(tickets) { tickets.remove(ticket) } else null
    }

    private fun trustedShape(context: Context, intent: Intent): Boolean {
        val explicit = intent.action == ACTION &&
            intent.component == ComponentName(context, ImportantAlarmService::class.java) &&
            intent.flags == 0
        val noPayload = intent.selector == null && intent.clipData == null && intent.type == null
        val noExtras = intent.extras == null && intent.categories.isNullOrEmpty()
        return explicit && noPayload && noExtras
    }

    internal fun attach(value: ImportantAlarmSessions) {
        sessions = value
    }

    internal fun detach(value: ImportantAlarmSessions) {
        if (sessions === value) sessions = null
    }

    internal fun reportFailure(token: AlertToken) {
        mutableFailures.tryEmit(token)
    }

    private fun ticketUri(ticket: String): Uri = "clender-alarm://ticket/$ticket".toUri()
}
