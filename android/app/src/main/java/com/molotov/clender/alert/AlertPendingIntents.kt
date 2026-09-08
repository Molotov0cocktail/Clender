package com.molotov.clender.alert

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.molotov.clender.app.MainActivity
import com.molotov.clender.app.alert.AlertKind
import com.molotov.clender.app.alert.AlertToken
import java.time.Instant
import java.util.Locale

data class AlertBroadcastAction(val token: AlertToken, val stop: Boolean)

object AlertPendingIntents {
    fun broadcast(context: Context, token: AlertToken, stop: Boolean = false): PendingIntent =
        PendingIntent.getBroadcast(context, 0, intent(context, token, stop), FLAGS)

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        FLAGS
    )

    internal fun intent(context: Context, token: AlertToken, stop: Boolean = false): Intent =
        Intent(context, EventAlertReceiver::class.java).apply {
            setPackage(context.packageName)
            action = if (stop) ACTION_STOP else ACTION_FIRE
            data = uri(token, stop)
        }

    fun validate(context: Context, intent: Intent): AlertBroadcastAction? = runCatching {
        val stop = when (intent.action) {
            ACTION_STOP -> true
            ACTION_FIRE -> false
            else -> error("Unknown alert action")
        }
        require(intent.component == ComponentName(context, EventAlertReceiver::class.java))
        require(intent.`package` == context.packageName && payloadAbsent(intent))
        val data = requireNotNull(intent.data)
        require(validUriShape(data))
        val id = requireNotNull(data.pathSegments[0].toLongOrNull()?.takeIf { it > 0 })
        val kind = AlertKind.valueOf(data.pathSegments[1].uppercase(Locale.ROOT))
        val time = requireNotNull(data.pathSegments[2].toLongOrNull()?.takeIf { it >= 0 })
        val revision = requireNotNull(data.getQueryParameter(REVISION))
        Instant.parse(revision)
        val token = AlertToken(id, kind, time, revision)
        require(uri(token, stop) == data)
        AlertBroadcastAction(token, stop)
    }.getOrNull()

    private fun payloadAbsent(intent: Intent): Boolean =
        intent.type == null && intent.selector == null &&
            intent.clipData == null && noExtraPayload(intent)

    private fun noExtraPayload(intent: Intent): Boolean =
        intent.categories.isNullOrEmpty() && intent.extras?.isEmpty != false

    private fun validUriShape(data: Uri): Boolean =
        data.scheme == SCHEME && data.host == AUTHORITY && validUriParts(data)

    private fun validUriParts(data: Uri): Boolean = data.pathSegments.size == PATH_SEGMENTS &&
        data.queryParameterNames == setOf(REVISION) && data.fragment == null

    private fun uri(token: AlertToken, stop: Boolean): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(AUTHORITY)
        .appendPath(token.eventId.toString())
        .appendPath(token.kind.name.lowercase(Locale.ROOT))
        .appendPath(token.triggerAtMillis.toString())
        .appendPath(if (stop) "stop" else "fire")
        .appendQueryParameter(REVISION, token.revision)
        .build()

    private const val ACTION_FIRE = "com.molotov.clender.alert.FIRE"
    private const val ACTION_STOP = "com.molotov.clender.alert.STOP"
    private const val SCHEME = "clender-alert"
    private const val AUTHORITY = "event"
    private const val REVISION = "revision"
    private const val PATH_SEGMENTS = 4
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}
