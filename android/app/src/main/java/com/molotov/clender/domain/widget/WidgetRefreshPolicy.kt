package com.molotov.clender.domain.widget

import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

enum class WidgetRefreshTrigger {
    LOCAL_MUTATION,
    REMOTE_VISIBLE_CHANGE,
    CONFIGURATION_CHANGE,
    FOREGROUND,
    MANUAL_LOCAL_REFRESH,
    DATE_BOUNDARY,
    BOOT
}

enum class WidgetRefreshFailure {
    INVALID_TARGET,
    TIME_OVERFLOW
}

/** The only schedule shape allowed for the future date-boundary worker. */
data class WidgetDateBoundarySchedule(
    val uniqueWorkName: String,
    val appWide: Boolean,
    val oneTime: Boolean,
    val replaceExisting: Boolean,
    val delay: Duration
)

/**
 * A pure decision for local widget rebuilding.
 *
 * An empty target set with no failure is the explicit no-op representation.
 * A failure never contains an exception, URL, credential, or other detail.
 */
data class WidgetRefreshPlan(
    val targetWidgetIds: Set<Int>,
    val dateBoundarySchedule: WidgetDateBoundarySchedule?,
    val failure: WidgetRefreshFailure?
)

object WidgetRefreshPolicy {
    const val DATE_BOUNDARY_WORK_NAME = "clender-widget-date-boundary"

    private val allowedTriggers = WidgetRefreshTrigger.entries.toSet()

    /**
     * Produces a local rebuild decision only. It does not enqueue work or read
     * wall-clock system time.
     */
    fun plan(
        trigger: WidgetRefreshTrigger,
        configuredWidgetIds: Set<Int>,
        targetWidgetId: Int? = null,
        clock: Clock,
        zoneId: ZoneId
    ): WidgetRefreshPlan {
        val targetIds = targetIdsFor(trigger, configuredWidgetIds, targetWidgetId)
        return when {
            targetIds == null -> failed(WidgetRefreshFailure.INVALID_TARGET)
            targetIds.isEmpty() -> WidgetRefreshPlan(emptySet(), null, null)
            else -> buildPlan(trigger, targetIds, clock, zoneId)
        }
    }

    private fun targetIdsFor(
        trigger: WidgetRefreshTrigger,
        configuredWidgetIds: Set<Int>,
        targetWidgetId: Int?
    ): Set<Int>? {
        val configured = configuredWidgetIds
            .asSequence()
            .filter { it > 0 }
            .toSet()
        return if (trigger !in allowedTriggers) {
            null
        } else {
            when (trigger) {
                WidgetRefreshTrigger.CONFIGURATION_CHANGE,
                WidgetRefreshTrigger.MANUAL_LOCAL_REFRESH ->
                    targetWidgetId?.takeIf { it > 0 }?.let(::setOf)

                WidgetRefreshTrigger.LOCAL_MUTATION,
                WidgetRefreshTrigger.REMOTE_VISIBLE_CHANGE,
                WidgetRefreshTrigger.FOREGROUND,
                WidgetRefreshTrigger.DATE_BOUNDARY,
                WidgetRefreshTrigger.BOOT -> configured
            }
        }
    }

    private fun buildPlan(
        trigger: WidgetRefreshTrigger,
        targetIds: Set<Int>,
        clock: Clock,
        zoneId: ZoneId
    ): WidgetRefreshPlan = if (trigger == WidgetRefreshTrigger.DATE_BOUNDARY) {
        nextDateBoundary(clock, zoneId)?.let { schedule ->
            WidgetRefreshPlan(targetIds, schedule, null)
        } ?: failed(WidgetRefreshFailure.TIME_OVERFLOW)
    } else {
        WidgetRefreshPlan(targetIds, null, null)
    }

    private fun nextDateBoundary(clock: Clock, zoneId: ZoneId): WidgetDateBoundarySchedule? = try {
        val now = clock.instant()
        val localDate = now.atZone(zoneId).toLocalDate()

        // Keep the final representable year fail-closed. A boundary near
        // LocalDate.MAX can otherwise be representable for the immediate
        // date while leaving no safe room for later date-boundary work.
        if (localDate.year >= LocalDate.MAX.year - 1) {
            null
        } else {
            val nextLocalDate = localDate.plusDays(1)
            val nextBoundary = nextLocalDate.atStartOfDay(zoneId).toInstant()
            val delay = Duration.between(now, nextBoundary)
            if (delay.isZero || delay.isNegative) {
                null
            } else {
                WidgetDateBoundarySchedule(
                    uniqueWorkName = DATE_BOUNDARY_WORK_NAME,
                    appWide = true,
                    oneTime = true,
                    replaceExisting = true,
                    delay = delay
                )
            }
        }
    } catch (_: DateTimeException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    private fun failed(failure: WidgetRefreshFailure): WidgetRefreshPlan =
        WidgetRefreshPlan(emptySet(), null, failure)
}
