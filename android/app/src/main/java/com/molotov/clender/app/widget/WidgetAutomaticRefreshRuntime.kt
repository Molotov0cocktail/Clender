package com.molotov.clender.app.widget

import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Local work belongs to the supplied widget owner, never to a Receipt waiter or Worker Job. */
class WidgetAutomaticRefreshRuntime(
    private val scope: CoroutineScope,
    dependencies: WidgetAutomaticRefreshDependencies
) : WidgetAutomaticRefreshPort {
    private val owner = SupervisorJob(requireNotNull(scope.coroutineContext[Job]))
    private val inbox = WidgetRefreshInbox()
    private val localUpdates = dependencies.localUpdates
    private val observation = WidgetRefreshObservation(scope, owner, dependencies.mutationVersion) {
        request(WidgetRefreshTrigger.LOCAL_MUTATION)
    }
    private val processor = WidgetRefreshProcessor(
        inbox,
        WidgetRefreshPorts(dependencies.ownedIds, localUpdates, dependencies.dateWork),
        observation,
        dependencies.clock,
        dependencies.zoneId
    )

    init {
        owner.invokeOnCompletion { inbox.close() }
        scope.launch(context = owner) { consume() }
    }

    override fun restore(): WidgetRefreshReceipt = submit(
        WidgetRefreshRequest(WidgetRefreshTrigger.FOREGROUND, restore = true)
    )

    override fun request(trigger: WidgetRefreshTrigger, targetId: Int?): WidgetRefreshReceipt {
        if (!owner.isActive) return rejectedWidgetRefresh(WidgetRefreshCompletion.CLOSED)
        val targeted = trigger == WidgetRefreshTrigger.CONFIGURATION_CHANGE ||
            trigger == WidgetRefreshTrigger.MANUAL_LOCAL_REFRESH
        val valid = if (targeted) targetId != null && targetId > 0 else targetId == null
        return if (valid) {
            submit(WidgetRefreshRequest(trigger, targetId))
        } else {
            rejectedWidgetRefresh(WidgetRefreshCompletion.INVALID_TARGET)
        }
    }

    override fun instancesChanged(deletedIds: Set<Int>): WidgetRefreshReceipt {
        val ids = deletedIds.filter { it > 0 }.toSet()
        return submit(
            WidgetRefreshRequest(
                WidgetRefreshTrigger.FOREGROUND,
                restore = ids.isEmpty(),
                deletedIds = ids
            )
        )
    }

    private fun submit(request: WidgetRefreshRequest): WidgetRefreshReceipt {
        val ticket = if (owner.isActive) inbox.register(request) else null
        if (ticket == null) return rejectedWidgetRefresh(WidgetRefreshCompletion.CLOSED)
        if (request.deletedIds.isEmpty()) {
            inbox.enqueue(ticket)
        } else {
            startDeletion(ticket)
        }
        return ticket.receipt
    }

    private fun startDeletion(ticket: WidgetRefreshTicket) {
        try {
            localUpdates.invalidate(ticket.request.deletedIds)
        } catch (cancelled: CancellationException) {
            inbox.deletionFinished(ticket)
            inbox.finish(ticket, WidgetRefreshCompletion.CLOSED)
            throw cancelled
        } catch (_: Exception) {
            ticket.deletionFailed = true
        }
        scope.launch(context = owner) { delete(ticket) }
    }

    private suspend fun delete(ticket: WidgetRefreshTicket) {
        try {
            localUpdates.delete(ticket.request.deletedIds)
        } catch (cancelled: CancellationException) {
            inbox.finish(ticket, WidgetRefreshCompletion.CLOSED)
            throw cancelled
        } catch (_: Exception) {
            ticket.deletionFailed = true
        } finally {
            inbox.deletionFinished(ticket)
        }
        inbox.enqueue(ticket)
    }

    private suspend fun consume() {
        try {
            while (inbox.wakeups.receiveCatching().isSuccess) {
                val tickets = inbox.take()
                if (tickets.isNotEmpty()) processor.process(tickets)
            }
        } finally {
            close()
        }
    }

    override fun close() {
        inbox.close()
        owner.cancel()
    }

    override suspend fun awaitClosed() {
        owner.join()
    }
}
