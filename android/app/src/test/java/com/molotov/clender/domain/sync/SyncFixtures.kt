package com.molotov.clender.domain.sync

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import java.time.Instant
import java.time.LocalDateTime

@Suppress("LongParameterList")
internal fun syncEvent(
    uid: String = syncUid(1),
    title: String = "事项",
    description: String = "说明",
    eventType: EventType = EventType.REMINDER,
    startTime: LocalDateTime = LocalDateTime.of(2026, 8, 3, 9, 0),
    endTime: LocalDateTime? = null,
    estimatedDurationMinutes: Int = 0,
    createdAt: Instant = Instant.parse("2026-08-03T00:00:00Z"),
    updatedAt: Instant = Instant.parse("2026-08-03T02:00:00Z"),
    deletedAt: Instant? = null
): Event = Event(
    id = 0L,
    eventType = eventType,
    title = title,
    startTime = startTime,
    endTime = endTime,
    description = description,
    estimatedDurationMinutes = estimatedDurationMinutes,
    createdAt = createdAt,
    syncUid = uid,
    updatedAt = updatedAt,
    deletedAt = deletedAt
)

internal fun syncUid(value: Int): String = value.toString(16).padStart(32, '0')

@Suppress("LongParameterList")
internal fun jsonRecord(
    uid: String = syncUid(1),
    titleJson: String = "\"事项\"",
    descriptionJson: String = "\"说明\"",
    eventTypeJson: String = "\"reminder\"",
    startTimeJson: String = "\"2026-08-03 09:00\"",
    endTimeJson: String = "null",
    durationJson: String = "0",
    createdAtJson: String = "\"2026-08-03T00:00:00.000000Z\"",
    updatedAtJson: String = "\"2026-08-03T02:00:00.000000Z\"",
    deletedAtJson: String = "null",
    extraField: String = ""
): String = """
    {
      "sync_uid":"$uid",
      "event_type":$eventTypeJson,
      "title":$titleJson,
      "start_time":$startTimeJson,
      "end_time":$endTimeJson,
      "description":$descriptionJson,
      "estimated_duration":$durationJson,
      "created_at":$createdAtJson,
      "updated_at":$updatedAtJson,
      "deleted_at":$deletedAtJson$extraField
    }
""".trimIndent()

internal fun jsonDocument(vararg records: String): ByteArray =
    """{"schema_version":1,"events":[${records.joinToString(",")}]}"""
        .toByteArray(Charsets.UTF_8)
