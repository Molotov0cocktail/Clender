package com.molotov.clender.app.widget

import kotlinx.coroutines.CompletableDeferred

interface WidgetRefreshReceipt {
    val accepted: Boolean

    suspend fun await(): WidgetRefreshCompletion
}

enum class WidgetRefreshCompletion {
    COMPLETED,
    NO_WIDGETS,
    INVALID_TARGET,
    SUPERSEDED,
    CLOSED,
    OWNERSHIP_FAILED,
    UPDATE_FAILED,
    SCHEDULE_FAILED,
    TIME_OVERFLOW
}

/** The completion deliberately has no caller Job as its parent. */
internal class PendingWidgetRefreshReceipt(override val accepted: Boolean = true) :
    WidgetRefreshReceipt {
    private val completion = CompletableDeferred<WidgetRefreshCompletion>()

    override suspend fun await(): WidgetRefreshCompletion = completion.await()

    fun complete(result: WidgetRefreshCompletion) {
        completion.complete(result)
    }
}

internal fun rejectedWidgetRefresh(result: WidgetRefreshCompletion): WidgetRefreshReceipt =
    PendingWidgetRefreshReceipt(accepted = false).also { it.complete(result) }
