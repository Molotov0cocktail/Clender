package com.molotov.clender.app.widget

import com.molotov.clender.domain.widget.WidgetDateBoundarySchedule
import com.molotov.clender.domain.widget.WidgetRefreshTrigger

interface WidgetAutomaticRefreshPort {
    fun restore(): WidgetRefreshReceipt

    fun request(trigger: WidgetRefreshTrigger, targetId: Int? = null): WidgetRefreshReceipt

    fun instancesChanged(deletedIds: Set<Int> = emptySet()): WidgetRefreshReceipt

    fun close()

    suspend fun awaitClosed()
}

fun interface WidgetOwnedIdsPort {
    suspend fun ownedIds(): Set<Int>
}

interface WidgetLocalUpdatePort {
    /** Nonblocking: invalidate existing tickets without initializing a coordinator or Room. */
    fun invalidate(ids: Set<Int>)

    suspend fun update(id: Int)

    /**
     * Called under the admission lock: only capture a coordinator generation/time ticket.
     * No data/render port access, I/O, resource initialization or suspension is allowed here.
     */
    fun prepareUpdate(id: Int): suspend () -> Unit = { update(id) }

    suspend fun delete(ids: Set<Int>)
}

interface WidgetDateWorkPort {
    /** Returns only after the platform enqueue operation has committed. */
    suspend fun replace(schedule: WidgetDateBoundarySchedule)

    /** Returns only after the platform cancellation operation has committed. */
    suspend fun cancel()
}
