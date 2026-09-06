package com.molotov.clender.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class WidgetBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !isSystemBootShape(intent)) return
        val finisher = BootPendingResultFinisher(goAsync())
        val owner = context.applicationContext as? WidgetAutomaticRefreshOwner
        if (owner == null) {
            finisher.finish()
            return
        }
        try {
            val job = owner.widgetAutomaticRefreshScope.launch {
                try {
                    withTimeout(BOOT_TIMEOUT_MILLIS) {
                        owner.widgetAutomaticRefreshRuntime
                            .request(WidgetRefreshTrigger.BOOT).await()
                    }
                } catch (_: Exception) {
                    // Completion and cancellation remain local; never expose runtime details.
                } finally {
                    finisher.finish()
                }
            }
            job.invokeOnCompletion { finisher.finish() }
        } catch (_: Exception) {
            finisher.finish()
        }
    }
}

private fun isSystemBootShape(intent: Intent): Boolean = runCatching {
    val extras = intent.extras
    val systemExtras = extras == null || extras.isEmpty ||
        (extras.keySet() == setOf(SYSTEM_USER_HANDLE) && extras.getInt(SYSTEM_USER_HANDLE, -1) >= 0)
    intent.action == Intent.ACTION_BOOT_COMPLETED && systemExtras &&
        intent.data == null && intent.type == null && intent.selector == null &&
        intent.clipData == null && intent.categories.isNullOrEmpty()
}.getOrDefault(false)

private class BootPendingResultFinisher(private val pending: BroadcastReceiver.PendingResult) {
    private val finished = AtomicBoolean(false)

    fun finish() {
        if (finished.compareAndSet(false, true)) pending.finish()
    }
}

private const val SYSTEM_USER_HANDLE = "android.intent.extra.user_handle"
private const val BOOT_TIMEOUT_MILLIS = 9_000L
