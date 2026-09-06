package com.molotov.clender.widget

import android.app.Application
import com.molotov.clender.app.widget.WidgetAutomaticRefreshPort
import com.molotov.clender.app.widget.WidgetRefreshCompletion
import com.molotov.clender.app.widget.WidgetRefreshReceipt
import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class WidgetPlatformTestApplication :
    Application(),
    WidgetAutomaticRefreshOwner {
    internal val ownerJob = SupervisorJob()
    override val widgetAutomaticRefreshScope = CoroutineScope(ownerJob + Dispatchers.Main.immediate)
    internal var port = PlatformRecordingRefreshPort()
    internal var runtimeReads = 0
    override val widgetAutomaticRefreshRuntime: WidgetAutomaticRefreshPort
        get() {
            runtimeReads += 1
            return port
        }
}

internal class PlatformRecordingRefreshPort : WidgetAutomaticRefreshPort {
    val requests = mutableListOf<Pair<WidgetRefreshTrigger, Int?>>()
    val awaitEntered = CompletableDeferred<Unit>()
    val awaitCancelled = CompletableDeferred<Unit>()
    val completion = CompletableDeferred<WidgetRefreshCompletion>()

    override fun request(trigger: WidgetRefreshTrigger, targetId: Int?): WidgetRefreshReceipt {
        requests += trigger to targetId
        return object : WidgetRefreshReceipt {
            override val accepted = true

            override suspend fun await(): WidgetRefreshCompletion {
                awaitEntered.complete(Unit)
                return try {
                    completion.await()
                } catch (cancelled: CancellationException) {
                    awaitCancelled.complete(Unit)
                    throw cancelled
                }
            }
        }
    }

    override fun restore(): WidgetRefreshReceipt = error("platform must dispatch exact trigger")
    override fun instancesChanged(deletedIds: Set<Int>): WidgetRefreshReceipt =
        error("platform must not mutate instances")

    override fun close() {
        completion.complete(WidgetRefreshCompletion.CLOSED)
    }

    override suspend fun awaitClosed() = Unit
}
