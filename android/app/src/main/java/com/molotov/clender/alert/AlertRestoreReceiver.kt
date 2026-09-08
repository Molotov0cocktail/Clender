package com.molotov.clender.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.ZoneId

class AlertRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESTORE_ACTIONS || !validRestoreIntent(intent)) return
        val finish = AlertReceiverFinisher(goAsync())
        try {
            val owner = context.applicationContext as? EventAlertRuntimeOwner
            if (owner == null) {
                finish.finish()
            } else {
                owner.alertRuntime.refresh().invokeOnCompletion { finish.finish() }
            }
        } catch (_: RuntimeException) {
            finish.finish()
        }
    }
}

internal fun validRestoreIntent(intent: Intent): Boolean = runCatching {
    if (intent.action !in RESTORE_ACTIONS || !restorePayloadAbsent(intent)) return false
    val extras = intent.extras
    if (extras == null || extras.isEmpty) return true
    when (intent.action) {
        Intent.ACTION_BOOT_COMPLETED -> extras.keySet() == setOf(USER_HANDLE) &&
            extras.getInt(USER_HANDLE, -1) >= 0

        Intent.ACTION_TIMEZONE_CHANGED -> extras.keySet() == setOf(TIME_ZONE) &&
            runCatching { ZoneId.of(extras.getString(TIME_ZONE)) }.isSuccess

        else -> false
    }
}.getOrDefault(false)

private fun restorePayloadAbsent(intent: Intent): Boolean =
    intent.data == null && intent.type == null &&
        restoreRoutingAbsent(intent)

private fun restoreRoutingAbsent(intent: Intent): Boolean =
    intent.selector == null && intent.clipData == null && intent.categories.isNullOrEmpty()

private const val USER_HANDLE = "android.intent.extra.user_handle"
private const val TIME_ZONE = "time-zone"
private val RESTORE_ACTIONS = setOf(
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_TIME_CHANGED,
    Intent.ACTION_TIMEZONE_CHANGED,
    "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
)
