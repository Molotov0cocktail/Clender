package com.molotov.clender.domain.ai

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.WallClockCodec
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

fun interface VisibleScheduleSource {
    suspend fun visibleEvents(): List<Event>
}

class FlowVisibleScheduleSource(private val events: Flow<List<Event>>) : VisibleScheduleSource {
    override suspend fun visibleEvents(): List<Event> = events.first()
}

class AiContextProvider(
    private val clock: Clock,
    private val schedules: VisibleScheduleSource,
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault
) {
    suspend fun build(): String {
        val instant = clock.instant()
        val zone = zoneProvider()
        val now = LocalDateTime.ofInstant(instant, zone)
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val visible = schedules.visibleEvents()
            .asSequence()
            .filter { it.deletedAt == null }
            .sortedWith(compareBy(Event::startTime, Event::id))
            .toList()
        return buildString {
            append("Current local date/time: ")
            append(WallClockCodec.format(now.withSecond(0).withNano(0)))
            append("; weekday: ")
            append(weekday)
            append(".\nVisible schedules:\n")
            if (visible.isEmpty()) {
                append("(none)")
            } else {
                visible.forEach { event ->
                    append("- id=")
                    append(event.id)
                    append(" type=")
                    append(event.eventType.name.lowercase())
                    append(" title=")
                    append(event.title.replace('\n', ' ').replace('\r', ' '))
                    append(" start=")
                    append(WallClockCodec.format(event.startTime))
                    event.endTime?.let { end ->
                        append(" end=")
                        append(WallClockCodec.format(end))
                    }
                    append(" duration=")
                    append(event.estimatedDurationMinutes)
                    append('\n')
                }
            }
        }.trimEnd()
    }
}
