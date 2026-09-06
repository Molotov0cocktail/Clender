package com.molotov.clender.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: EventEntity): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(entity: EventEntity): Int

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun findById(id: Long): EventEntity?

    @Query("SELECT * FROM events WHERE sync_uid = :syncUid")
    suspend fun findBySyncUid(syncUid: String): EventEntity?

    @Query(
        """
        SELECT * FROM events
        WHERE deleted_at IS NULL
        ORDER BY start_time, COALESCE(end_time, start_time), id
        """
    )
    fun observeVisible(): Flow<List<EventEntity>>

    @Query(
        """
        SELECT * FROM events
        WHERE deleted_at IS NULL
          AND (
            (event_type = 'reminder' AND start_time >= :rangeStart AND start_time < :rangeEnd)
            OR
            (event_type = 'timespan' AND start_time < :rangeEnd AND end_time > :rangeStart)
          )
        ORDER BY start_time, COALESCE(end_time, start_time), id
        """
    )
    fun observeOverlapping(
        rangeStart: LocalDateTime,
        rangeEnd: LocalDateTime
    ): Flow<List<EventEntity>>

    @Query(
        """
        SELECT * FROM events
        WHERE deleted_at IS NULL
          AND (
            (event_type = 'reminder' AND start_time >= :rangeStart AND start_time < :rangeEnd)
            OR
            (event_type = 'timespan' AND start_time < :rangeEnd AND end_time > :rangeStart)
          )
        ORDER BY start_time, COALESCE(end_time, start_time), id
        """
    )
    suspend fun findOverlapping(
        rangeStart: LocalDateTime,
        rangeEnd: LocalDateTime
    ): List<EventEntity>

    @Query("SELECT * FROM events ORDER BY sync_uid")
    suspend fun syncSnapshot(): List<EventEntity>
}

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun findById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY created_at, id")
    suspend fun listOrdered(): List<ConversationEntity>

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("UPDATE conversations SET token_count = :tokenCount WHERE id = :id")
    suspend fun updateTokenCount(id: String, tokenCount: Int): Int

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String): Int
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: MessageEntity): Long

    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
        ORDER BY timestamp, id
        """
    )
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun countForConversation(conversationId: String): Int

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteForConversation(conversationId: String): Int
}
