package com.molotov.clender.app.widget

import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class WidgetRefreshPorts(
    val ownedIds: WidgetOwnedIdsPort,
    val localUpdates: WidgetLocalUpdatePort,
    val dateWork: WidgetDateWorkPort
)

internal class WidgetRefreshProcessor(
    private val inbox: WidgetRefreshInbox,
    private val ports: WidgetRefreshPorts,
    private val observation: WidgetRefreshObservation,
    private val clock: Clock,
    private val zoneId: () -> ZoneId
) {
    private val dates = WidgetRefreshDateCoordinator(ports.dateWork)

    suspend fun process(tickets: List<WidgetRefreshTicket>) {
        val owned = ownedIds(tickets) ?: return
        currentCoroutineContext().ensureActive()
        inbox.restoreOwnership(tickets, owned)
        observation.reconcile(inbox.liveIds(owned).isNotEmpty())
        val moment = moment(tickets) ?: return
        val batch = WidgetRefreshBatch(inbox, ports.localUpdates)
        batch.prepare(tickets, owned, moment)
        batch.update()
        currentCoroutineContext().ensureActive()
        val dateResult = reconcileDate(tickets, owned, moment)
        batch.finish(dateResult)
    }

    private suspend fun ownedIds(tickets: List<WidgetRefreshTicket>): Set<Int>? = try {
        ports.ownedIds.ownedIds().filter { it > 0 }.toSortedSet()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        tickets.forEach { inbox.finish(it, WidgetRefreshCompletion.OWNERSHIP_FAILED) }
        null
    }

    private fun moment(tickets: List<WidgetRefreshTicket>): WidgetRefreshMoment? = try {
        val zone = zoneId()
        WidgetRefreshMoment(Clock.fixed(clock.instant(), zone), zone)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        tickets.forEach { inbox.finish(it, WidgetRefreshCompletion.TIME_OVERFLOW) }
        null
    }

    private suspend fun reconcileDate(
        tickets: List<WidgetRefreshTicket>,
        owned: Set<Int>,
        moment: WidgetRefreshMoment
    ): WidgetRefreshCompletion {
        val live = inbox.liveIds(owned)
        val force = tickets.any { it.request.trigger == WidgetRefreshTrigger.DATE_BOUNDARY }
        return if (live.isEmpty() || tickets.any { it.request.coordinatesDate }) {
            dates.reconcile(live, moment, force)
        } else {
            WidgetRefreshCompletion.COMPLETED
        }
    }
}
