package com.molotov.clender.data.local

import androidx.room.withTransaction
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import com.molotov.clender.core.model.UtcInstantCodec
import com.molotov.clender.domain.conversation.ConversationRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomConversationRepository(private val database: ClenderDatabase) : ConversationRepository {
    private val conversationDao = database.conversationDao()
    private val messageDao = database.messageDao()

    override suspend fun create(conversation: Conversation): Conversation {
        val normalized = conversation.copy(createdAt = normalizeInstant(conversation.createdAt))
        validateConversation(normalized)
        conversationDao.insert(normalized.toEntity())
        return normalized
    }

    override suspend fun findConversation(id: String): Conversation? {
        require(id.isNotBlank()) { "Conversation ID cannot be blank" }
        return conversationDao.findById(id)?.toDomain()
    }

    override suspend fun listConversations(): List<Conversation> =
        conversationDao.listOrdered().map(ConversationEntity::toDomain)

    override fun observeMessages(conversationId: String): Flow<List<Message>> {
        require(conversationId.isNotBlank()) { "Conversation ID cannot be blank" }
        return messageDao.observeForConversation(conversationId).map { entities ->
            entities.map(MessageEntity::toDomain)
        }
    }

    override suspend fun appendMessageAndIncrementTokens(
        conversationId: String,
        role: MessageRole,
        content: String,
        timestamp: Instant,
        tokenDelta: Int
    ): Message {
        require(conversationId.isNotBlank()) { "Conversation ID cannot be blank" }
        require(content.isNotEmpty()) { "Message content cannot be empty" }
        require(tokenDelta >= 0) { "Token delta cannot be negative" }
        val normalizedTimestamp = normalizeInstant(timestamp)
        return database.withTransaction {
            val conversation = requireNotNull(conversationDao.findById(conversationId)) {
                "Conversation does not exist"
            }
            require(conversation.tokenCount <= Int.MAX_VALUE - tokenDelta) {
                "Token count overflow"
            }
            val entity = MessageEntity(
                conversationId = conversationId,
                role = role,
                content = content,
                timestamp = normalizedTimestamp
            )
            val messageId = messageDao.insert(entity)
            check(
                conversationDao.updateTokenCount(
                    conversationId,
                    conversation.tokenCount + tokenDelta
                ) ==
                    1
            )
            entity.copy(id = messageId).toDomain()
        }
    }

    override suspend fun rename(id: String, title: String): Conversation? {
        require(id.isNotBlank()) { "Conversation ID cannot be blank" }
        val normalizedTitle = title.trim()
        require(normalizedTitle.isNotEmpty()) { "Conversation title cannot be blank" }
        return database.withTransaction {
            val current = conversationDao.findById(id) ?: return@withTransaction null
            check(conversationDao.updateTitle(id, normalizedTitle) == 1)
            current.copy(title = normalizedTitle).toDomain()
        }
    }

    override suspend fun clearMessagesAndResetTokens(id: String): Boolean {
        require(id.isNotBlank()) { "Conversation ID cannot be blank" }
        return database.withTransaction {
            if (conversationDao.findById(id) == null) return@withTransaction false
            messageDao.deleteForConversation(id)
            check(conversationDao.updateTokenCount(id, 0) == 1)
            true
        }
    }

    override suspend fun delete(id: String): Boolean {
        require(id.isNotBlank()) { "Conversation ID cannot be blank" }
        return conversationDao.delete(id) == 1
    }

    private fun validateConversation(conversation: Conversation) {
        require(conversation.id.isNotBlank()) { "Conversation ID cannot be blank" }
        require(conversation.title.trim().isNotEmpty()) { "Conversation title cannot be blank" }
        require(conversation.tokenCount >= 0) { "Token count cannot be negative" }
    }
}

private fun normalizeInstant(value: Instant): Instant =
    UtcInstantCodec.parse(UtcInstantCodec.format(value))
