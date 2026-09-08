package com.molotov.clender.app.alert

import com.molotov.clender.core.model.Event
import java.time.Instant
import java.time.ZoneId

object AlertPlanFactory {
    fun timerDeadline(event: Event, zone: ZoneId): Instant? =
        event.timerMinutes.takeIf { it in 1..MAX_TIMER_MINUTES }?.let { minutes ->
            runCatching {
                event.startTime.atZone(zone).toInstant().plusSeconds(minutes * SECONDS_PER_MINUTE)
                    .takeIf { it.atZone(zone).year in MIN_YEAR..MAX_YEAR }
            }.getOrNull()
        }

    fun tokens(event: Event, zone: ZoneId): List<AlertToken> {
        if (event.deletedAt != null) return emptyList()
        val start = event.startTime.atZone(zone).toInstant()
        return buildList {
            if (event.notificationEnabled &&
                !event.alarmEnabled
            ) {
                add(token(event, AlertKind.NOTIFICATION, start))
            }
            if (event.alarmEnabled) add(token(event, AlertKind.ALARM, start))
            if (event.timerMinutes > 0) {
                add(token(event, AlertKind.TIMER, requireNotNull(timerDeadline(event, zone))))
            }
        }
    }

    private fun token(event: Event, kind: AlertKind, time: Instant) =
        AlertToken(event.id, kind, time.toEpochMilli(), event.updatedAt.toString())
}

private const val MAX_TIMER_MINUTES = 1440
private const val SECONDS_PER_MINUTE = 60L
private const val MIN_YEAR = 1
private const val MAX_YEAR = 9999
