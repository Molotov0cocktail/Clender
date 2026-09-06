package com.molotov.clender.app.widget

import com.molotov.clender.domain.widget.WidgetRefreshPolicy
import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

internal data class WidgetRefreshMoment(val clock: Clock, val zoneId: ZoneId)

private data class WidgetDateWorkIdentity(
    val date: LocalDate,
    val zoneId: ZoneId,
    val boundary: Instant,
    val ownedIds: Set<Int>
)

/** Accessed only by the single refresh consumer, including deletion reconciliation. */
internal class WidgetRefreshDateCoordinator(private val work: WidgetDateWorkPort) {
    private var scheduled: WidgetDateWorkIdentity? = null
    private var knownEmpty = false

    suspend fun reconcile(
        ids: Set<Int>,
        moment: WidgetRefreshMoment,
        force: Boolean
    ): WidgetRefreshCompletion = try {
        if (ids.isEmpty()) cancelEmpty() else schedule(ids, moment, force)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        WidgetRefreshCompletion.SCHEDULE_FAILED
    }

    private suspend fun cancelEmpty(): WidgetRefreshCompletion {
        if (!knownEmpty) work.cancel()
        scheduled = null
        knownEmpty = true
        return WidgetRefreshCompletion.NO_WIDGETS
    }

    private suspend fun schedule(
        ids: Set<Int>,
        moment: WidgetRefreshMoment,
        force: Boolean
    ): WidgetRefreshCompletion {
        val plan = WidgetRefreshPolicy.plan(
            WidgetRefreshTrigger.DATE_BOUNDARY,
            ids,
            clock = moment.clock,
            zoneId = moment.zoneId
        )
        val schedule = plan.dateBoundarySchedule ?: return WidgetRefreshCompletion.TIME_OVERFLOW
        val instant = moment.clock.instant()
        val identity = WidgetDateWorkIdentity(
            instant.atZone(moment.zoneId).toLocalDate(),
            moment.zoneId,
            instant.plus(schedule.delay),
            ids.toSet()
        )
        if (force || scheduled != identity) {
            work.replace(schedule)
            scheduled = identity
        }
        knownEmpty = false
        return WidgetRefreshCompletion.COMPLETED
    }
}
