package com.molotov.clender.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver.PendingResult
import android.content.Context
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Application-owned entry point used by the platform Widget broadcast adapter. */
interface WidgetProviderRuntime {
    suspend fun update(appWidgetManager: AppWidgetManager, appWidgetIds: IntArray)

    suspend fun optionsChanged(appWidgetManager: AppWidgetManager, appWidgetId: Int)

    suspend fun localRefresh(appWidgetManager: AppWidgetManager, appWidgetId: Int)

    suspend fun delete(appWidgetIds: IntArray)

    /** Reserve deletion before a broadcast coroutine can be delayed behind an update. */
    fun prepareDelete(appWidgetIds: IntArray): suspend () -> Unit = { delete(appWidgetIds) }
}

/** Implemented by the production [android.app.Application] without eager runtime creation. */
interface WidgetProviderRuntimeOwner {
    val widgetProviderScope: CoroutineScope
    val widgetProviderRuntime: WidgetProviderRuntime
}

class ClenderWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        launchCallback(context, pendingResult) {
            update(appWidgetManager, appWidgetIds.copyOf())
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        val pendingResult = goAsync()
        launchCallback(context, pendingResult) {
            optionsChanged(appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        val operation = try {
            (context.applicationContext as? WidgetProviderRuntimeOwner)
                ?.widgetProviderRuntime?.prepareDelete(appWidgetIds.copyOf())
        } catch (_: Exception) {
            null
        }
        launchCallback(context, pendingResult) {
            operation?.invoke()
        }
    }

    private fun launchCallback(
        context: Context,
        pendingResult: PendingResult,
        operation: suspend WidgetProviderRuntime.() -> Unit
    ) {
        val finisher = PendingResultFinisher(pendingResult)
        val owner = context.applicationContext as? WidgetProviderRuntimeOwner
        if (owner == null) {
            finisher.finish()
            return
        }
        try {
            val job = owner.widgetProviderScope.launch {
                try {
                    withTimeout(PROVIDER_TIMEOUT_MILLIS) {
                        owner.widgetProviderRuntime.operation()
                    }
                } catch (_: Throwable) {
                    // Platform broadcasts expose no private failure details and must still finish.
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

private class PendingResultFinisher(private val pendingResult: PendingResult) {
    private val finished = AtomicBoolean(false)

    fun finish() {
        if (finished.compareAndSet(false, true)) {
            pendingResult.finish()
        }
    }
}

private const val PROVIDER_TIMEOUT_MILLIS = 9_000L
