package com.molotov.clender.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import java.time.Instant
import java.time.LocalDateTime

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["start_time"], name = "idx_events_start_time"),
        Index(value = ["sync_uid"], name = "idx_events_sync_uid", unique = true),
        Index(value = ["deleted_at"], name = "idx_events_deleted_at")
    ]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "event_type")
    val eventType: EventType,
    val title: String,
    @ColumnInfo(name = "start_time")
    val startTime: LocalDateTime,
    @ColumnInfo(name = "end_time")
    val endTime: LocalDateTime?,
    val description: String,
    @ColumnInfo(name = "estimated_duration")
    val estimatedDurationMinutes: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "sync_uid")
    val syncUid: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Instant?,
    @ColumnInfo(name = "notification_enabled", defaultValue = "0")
    val notificationEnabled: Boolean = false,
    @ColumnInfo(name = "alarm_enabled", defaultValue = "0")
    val alarmEnabled: Boolean = false,
    @ColumnInfo(name = "timer_minutes", defaultValue = "0")
    val timerMinutes: Int = 0
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "token_count")
    val tokenCount: Int
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            value = ["conversation_id", "timestamp"],
            name = "idx_messages_conversation_timestamp"
        )
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Instant
)

internal fun EventEntity.toDomain(): Event = Event(
    id = id,
    eventType = eventType,
    title = title,
    startTime = startTime,
    endTime = endTime,
    description = description,
    estimatedDurationMinutes = estimatedDurationMinutes,
    createdAt = createdAt,
    syncUid = syncUid,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    notificationEnabled = notificationEnabled,
    alarmEnabled = alarmEnabled,
    timerMinutes = timerMinutes
)

internal fun Event.toEntity(): EventEntity = EventEntity(
    id = id,
    eventType = eventType,
    title = title,
    startTime = startTime,
    endTime = endTime,
    description = description,
    estimatedDurationMinutes = estimatedDurationMinutes,
    createdAt = createdAt,
    syncUid = syncUid,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    notificationEnabled = notificationEnabled,
    alarmEnabled = alarmEnabled,
    timerMinutes = timerMinutes
)

internal fun ConversationEntity.toDomain(): Conversation =
    Conversation(id, title, createdAt, tokenCount)

internal fun Conversation.toEntity(): ConversationEntity =
    ConversationEntity(id, title, createdAt, tokenCount)

internal fun MessageEntity.toDomain(): Message =
    Message(id, conversationId, role, content, timestamp)
