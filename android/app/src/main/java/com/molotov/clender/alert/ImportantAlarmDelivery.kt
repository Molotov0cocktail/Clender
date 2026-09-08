package com.molotov.clender.alert

import android.app.Notification
import android.content.Context
import android.net.Uri
import com.molotov.clender.app.alert.AlertToken
import com.molotov.clender.core.model.Event
import kotlinx.coroutines.flow.Flow

interface ImportantAlarmDelivery {
    val failures: Flow<AlertToken>
    fun start(event: Event, token: AlertToken, notification: Notification, sound: Uri?): Boolean
    fun stop(eventId: Long)
}

internal class ForegroundAlarmDelivery(private val context: Context) : ImportantAlarmDelivery {
    override val failures: Flow<AlertToken> get() = ImportantAlarmPlayback.failures
    override fun start(
        event: Event,
        token: AlertToken,
        notification: Notification,
        sound: Uri?
    ): Boolean = ImportantAlarmPlayback.start(context, event, token, notification, sound)

    override fun stop(eventId: Long) = ImportantAlarmPlayback.stop(eventId)
}
