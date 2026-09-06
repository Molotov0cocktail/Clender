package com.molotov.clender.widget

import android.content.BroadcastReceiver
import android.content.BroadcastReceiver.PendingResult
import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Non-exported adapter for one ownership-validated, local-only Widget rebuild. */
class WidgetLocalRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = WidgetActionIntentContract.validateLocalRefresh(context, intent) ?: return
        val owner = context.applicationContext as? WidgetProviderRuntimeOwner
        val pendingResult = goAsync()
        val finisher = LocalRefreshPendingResultFinisher(pendingResult)
        if (owner == null) {
            finisher.finish()
            return
        }
        try {
            val job = owner.widgetProviderScope.launch {
                try {
                    withTimeout(LOCAL_REFRESH_TIMEOUT_MILLIS) {
                        owner.widgetProviderRuntime.localRefresh(
                            android.appwidget.AppWidgetManager.getInstance(context),
                            action.widgetId
                        )
                    }
                } catch (_: Throwable) {
                    // The private local action exposes no runtime or cancellation detail.
                } finally {
                    finisher.finish()
                }
            }
            job.invokeOnCompletion { finisher.finish() }
        } catch (_: Throwable) {
            finisher.finish()
        }
    }
}

private class LocalRefreshPendingResultFinisher(private val pendingResult: PendingResult) {
    private val finished = AtomicBoolean(false)

    fun finish() {
        if (finished.compareAndSet(false, true)) {
            pendingResult.finish()
        }
    }
}

private const val LOCAL_REFRESH_TIMEOUT_MILLIS = 9_000L
