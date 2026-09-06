package com.molotov.clender.app.widget

import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import kotlinx.coroutines.channels.Channel

internal data class WidgetRefreshRequest(
    val trigger: WidgetRefreshTrigger,
    val targetId: Int? = null,
    val restore: Boolean = false,
    val deletedIds: Set<Int> = emptySet()
) {
    val coordinatesDate: Boolean
        get() = restore || deletedIds.isNotEmpty() || trigger in DATE_TRIGGERS

    companion object {
        private val DATE_TRIGGERS = setOf(
            WidgetRefreshTrigger.DATE_BOUNDARY,
            WidgetRefreshTrigger.FOREGROUND,
            WidgetRefreshTrigger.BOOT
        )
    }
}

internal data class WidgetRefreshTicket(
    val request: WidgetRefreshRequest,
    val revision: Long,
    val receipt: PendingWidgetRefreshReceipt = PendingWidgetRefreshReceipt()
) {
    @Volatile
    var deletionFailed = false
}

/** Only bookkeeping is locked. No suspend port is invoked while holding this lock. */
internal class WidgetRefreshInbox {
    private val lock = Any()
    private val pending = mutableListOf<WidgetRefreshTicket>()
    private val outstanding = mutableSetOf<WidgetRefreshTicket>()
    private val deletedAt = mutableMapOf<Int, Long>()
    private val blocked = mutableSetOf<Int>()
    private val deleting = mutableMapOf<Int, Int>()
    private var revision = 0L
    private var closed = false
    val wakeups = Channel<Unit>(Channel.CONFLATED)

    fun register(request: WidgetRefreshRequest): WidgetRefreshTicket? = synchronized(lock) {
        if (closed) return@synchronized null
        if (request.deletedIds.isNotEmpty()) {
            revision++
            request.deletedIds.forEach { id ->
                deletedAt[id] = revision
                blocked.add(id)
                deleting[id] = (deleting[id] ?: 0) + 1
            }
        }
        WidgetRefreshTicket(request, revision).also { outstanding.add(it) }
    }

    fun enqueue(ticket: WidgetRefreshTicket) {
        synchronized(lock) {
            if (closed) return
            pending.add(ticket)
            wakeups.trySend(Unit)
        }
    }

    fun take(): List<WidgetRefreshTicket> = synchronized(lock) {
        pending.toList().also { pending.clear() }
    }

    fun deletionFinished(ticket: WidgetRefreshTicket) {
        synchronized(lock) {
            ticket.request.deletedIds.forEach { id ->
                val remaining = (deleting[id] ?: 1) - 1
                if (remaining == 0) {
                    deleting.remove(id)
                    // A restore accepted during deletion cannot revive its earlier snapshot.
                    deletedAt[id] = ++revision
                } else {
                    deleting[id] = remaining
                }
            }
        }
    }

    fun restoreOwnership(tickets: List<WidgetRefreshTicket>, owned: Set<Int>) {
        synchronized(lock) {
            if (closed) return
            val restores = tickets.filter { it.request.restore }
            blocked.removeAll { id ->
                id in owned && id !in deleting &&
                    restores.any { it.revision >= (deletedAt[id] ?: 0L) }
            }
        }
    }

    fun current(ticket: WidgetRefreshTicket, id: Int): Boolean = synchronized(lock) {
        !closed && id !in blocked && ticket.revision >= (deletedAt[id] ?: 0L)
    }

    /** Share deletion admission's lock, but return the suspend operation without invoking it. */
    fun prepare(
        ticket: WidgetRefreshTicket,
        id: Int,
        prepare: () -> (suspend () -> Unit)
    ): (suspend () -> Unit)? = synchronized(lock) {
        if (current(ticket, id)) prepare() else null
    }

    fun liveIds(owned: Set<Int>): Set<Int> = synchronized(lock) {
        if (closed) emptySet() else owned - blocked
    }

    fun finish(ticket: WidgetRefreshTicket, result: WidgetRefreshCompletion) {
        synchronized(lock) {
            outstanding.remove(ticket)
            ticket.receipt.complete(if (closed) WidgetRefreshCompletion.CLOSED else result)
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            pending.clear()
            outstanding.forEach { it.receipt.complete(WidgetRefreshCompletion.CLOSED) }
            outstanding.clear()
            wakeups.close()
        }
    }
}
