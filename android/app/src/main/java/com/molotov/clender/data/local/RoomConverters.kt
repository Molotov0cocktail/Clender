package com.molotov.clender.data.local

import androidx.room.TypeConverter
import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.MessageRole
import com.molotov.clender.core.model.UtcInstantCodec
import com.molotov.clender.core.model.WallClockCodec
import java.time.Instant
import java.time.LocalDateTime

class RoomConverters {
    @TypeConverter
    fun eventTypeToString(value: EventType): String = when (value) {
        EventType.REMINDER -> "reminder"
        EventType.TIMESPAN -> "timespan"
    }

    @TypeConverter
    fun stringToEventType(value: String): EventType = when (value) {
        "reminder" -> EventType.REMINDER
        "timespan" -> EventType.TIMESPAN
        else -> throw IllegalArgumentException("Unknown event type")
    }

    @TypeConverter
    fun messageRoleToString(value: MessageRole): String = value.name.lowercase()

    @TypeConverter
    fun stringToMessageRole(value: String): MessageRole = when (value) {
        "user" -> MessageRole.USER
        "assistant" -> MessageRole.ASSISTANT
        "think" -> MessageRole.THINK
        else -> throw IllegalArgumentException("Unknown message role")
    }

    @TypeConverter
    fun localDateTimeToString(value: LocalDateTime): String = WallClockCodec.format(value)

    @TypeConverter
    fun stringToLocalDateTime(value: String): LocalDateTime = WallClockCodec.parse(value)

    @TypeConverter
    fun instantToString(value: Instant): String = UtcInstantCodec.format(value)

    @TypeConverter
    fun stringToInstant(value: String): Instant = UtcInstantCodec.parse(value)
}
