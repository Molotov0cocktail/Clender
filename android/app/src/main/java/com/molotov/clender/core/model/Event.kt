package com.molotov.clender.core.model

import java.time.Instant
import java.time.LocalDateTime

enum class EventType(val wireValue: String) {
    REMINDER("reminder"),
    TIMESPAN("timespan");

    companion object {
        fun fromWire(value: String): EventType = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown event type")
    }
}

data class Event(
    val id: Long,
    val eventType: EventType,
    val title: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime?,
    val description: String,
    val estimatedDurationMinutes: Int,
    val createdAt: Instant,
    val syncUid: String,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val notificationEnabled: Boolean = false,
    val alarmEnabled: Boolean = false,
    val timerMinutes: Int = 0
)
