package com.molotov.clender.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import com.molotov.clender.app.widget.WidgetAutomaticRefreshDependencies
import com.molotov.clender.app.widget.WidgetAutomaticRefreshRuntime
import com.molotov.clender.app.widget.WidgetDateWorkPort
import com.molotov.clender.app.widget.WidgetLocalUpdatePort
import com.molotov.clender.app.widget.WidgetOwnedIdsPort
import com.molotov.clender.app.widget.WidgetRefreshReceipt
import com.molotov.clender.domain.widget.WidgetDateBoundarySchedule
import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Real runtime and receipts, controlled local work, no database or WorkManager fixture. */
class WidgetProviderTimeoutApplication :
    Application(),
    WidgetProviderRuntimeOwner {
    internal val ownerJob = SupervisorJob()
    override val widgetProviderScope = CoroutineScope(ownerJob + Dispatchers.Main.immediate)
    internal val release = CompletableDeferred<Unit>()
    internal var workEntered = 0
    internal var workCompleted = 0
    internal var workCancelled = false
    internal var waitCancelled = false
    internal var invalidations = 0
    internal var receipt: WidgetRefreshReceipt? = null
    internal val automatic = WidgetAutomaticRefreshRuntime(
        scope = widgetProviderScope,
        dependencies = WidgetAutomaticRefreshDependencies(
            ownedIds = WidgetOwnedIdsPort { setOf(1) },
            localUpdates = object : WidgetLocalUpdatePort {
                override fun invalidate(ids: Set<Int>) {
                    invalidations++
                }

                override suspend fun update(id: Int) {
                    doLocalWork()
                }

                override suspend fun delete(ids: Set<Int>) {
                    doLocalWork()
                }
            },
            dateWork = object : WidgetDateWorkPort {
                override suspend fun replace(schedule: WidgetDateBoundarySchedule) = Unit
                override suspend fun cancel() = Unit
            },
            mutationVersion = MutableStateFlow(0L),
            clock = Clock.fixed(Instant.parse("2026-09-06T10:00:00Z"), ZoneOffset.UTC),
            zoneId = { ZoneOffset.UTC }
        )
    )
    override val widgetProviderRuntime = object : WidgetProviderRuntime {
        override suspend fun update(appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
            awaitReceipt(automatic.restore())
        }

        override suspend fun optionsChanged(appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            awaitReceipt(automatic.request(WidgetRefreshTrigger.CONFIGURATION_CHANGE, appWidgetId))
        }

        override suspend fun localRefresh(appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            awaitReceipt(automatic.request(WidgetRefreshTrigger.MANUAL_LOCAL_REFRESH, appWidgetId))
        }

        override suspend fun delete(appWidgetIds: IntArray) {
            prepareDelete(appWidgetIds).invoke()
        }

        override fun prepareDelete(appWidgetIds: IntArray): suspend () -> Unit {
            val accepted = automatic.instancesChanged(appWidgetIds.toSet())
            receipt = accepted
            return { awaitReceipt(accepted) }
        }
    }

    private suspend fun doLocalWork() {
        workEntered++
        try {
            release.await()
            workCompleted++
        } catch (cancelled: CancellationException) {
            workCancelled = true
            throw cancelled
        }
    }

    private suspend fun awaitReceipt(accepted: WidgetRefreshReceipt) {
        receipt = accepted
        try {
            accepted.await()
        } catch (cancelled: CancellationException) {
            waitCancelled = true
            throw cancelled
        }
    }
}
