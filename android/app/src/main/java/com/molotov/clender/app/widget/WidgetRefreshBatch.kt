package com.molotov.clender.app.widget

import com.molotov.clender.domain.widget.WidgetRefreshPolicy
import kotlinx.coroutines.CancellationException

internal class WidgetRefreshBatch(
    private val inbox: WidgetRefreshInbox,
    private val localUpdates: WidgetLocalUpdatePort
) {
    private val targets = linkedMapOf<WidgetRefreshTicket, Set<Int>>()
    private val results = linkedMapOf<WidgetRefreshTicket, WidgetRefreshCompletion>()

    fun prepare(tickets: List<WidgetRefreshTicket>, owned: Set<Int>, moment: WidgetRefreshMoment) {
        tickets.forEach { ticket -> prepareOne(ticket, owned, moment) }
    }

    private fun prepareOne(
        ticket: WidgetRefreshTicket,
        owned: Set<Int>,
        moment: WidgetRefreshMoment
    ) {
        val request = ticket.request
        val target = request.targetId
        val plan = WidgetRefreshPolicy.plan(
            request.trigger,
            owned,
            target,
            moment.clock,
            moment.zoneId
        )
        val valid = plan.targetWidgetIds.filter { it in owned && inbox.current(ticket, it) }.toSet()
        targets[ticket] = valid
        results[ticket] = when {
            target != null && !inbox.current(ticket, target) -> WidgetRefreshCompletion.SUPERSEDED
            target != null && target !in owned -> WidgetRefreshCompletion.INVALID_TARGET
            plan.failure != null -> WidgetRefreshCompletion.TIME_OVERFLOW
            valid.isEmpty() -> WidgetRefreshCompletion.NO_WIDGETS
            else -> WidgetRefreshCompletion.COMPLETED
        }
    }

    suspend fun update() {
        targets.values.flatten().toSortedSet().forEach { id ->
            updateRecipients(id)
        }
    }

    private suspend fun updateRecipients(id: Int) {
        val recipients = targets.filter { (ticket, ids) ->
            id in ids && inbox.current(ticket, id)
        }.keys
        if (recipients.isNotEmpty()) {
            val result = updateOne(id, recipients)
            if (result != WidgetRefreshCompletion.COMPLETED) {
                recipients.forEach { results[it] = result }
            }
        }
    }

    private suspend fun updateOne(
        id: Int,
        recipients: Set<WidgetRefreshTicket>
    ): WidgetRefreshCompletion = try {
        invokePrepared(id, recipients)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        WidgetRefreshCompletion.UPDATE_FAILED
    }

    private suspend fun invokePrepared(
        id: Int,
        recipients: Set<WidgetRefreshTicket>
    ): WidgetRefreshCompletion {
        for (ticket in recipients) {
            val operation = inbox.prepare(ticket, id) { localUpdates.prepareUpdate(id) }
            if (operation != null) {
                operation()
                return WidgetRefreshCompletion.COMPLETED
            }
        }
        return WidgetRefreshCompletion.SUPERSEDED
    }

    fun finish(scheduleResult: WidgetRefreshCompletion) {
        results.forEach { (ticket, result) ->
            val target = ticket.request.targetId
            val finalResult = when {
                target != null && !inbox.current(ticket, target) ->
                    WidgetRefreshCompletion.SUPERSEDED

                ticket.deletionFailed -> WidgetRefreshCompletion.UPDATE_FAILED

                result in REFRESH_SUCCESS && scheduleResult in DATE_FAILURES -> scheduleResult

                else -> result
            }
            inbox.finish(ticket, finalResult)
        }
    }

    companion object {
        private val REFRESH_SUCCESS = setOf(
            WidgetRefreshCompletion.COMPLETED,
            WidgetRefreshCompletion.NO_WIDGETS
        )

        private val DATE_FAILURES = setOf(
            WidgetRefreshCompletion.SCHEDULE_FAILED,
            WidgetRefreshCompletion.TIME_OVERFLOW
        )
    }
}
