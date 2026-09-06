package com.molotov.clender.domain.conversation

import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import java.time.Instant
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    suspend fun create(conversation: Conversation): Conversation

    suspend fun findConversation(id: String): Conversation?

    suspend fun listConversations(): List<Conversation>

    fun observeMessages(conversationId: String): Flow<List<Message>>

    suspend fun appendMessageAndIncrementTokens(
        conversationId: String,
        role: MessageRole,
        content: String,
        timestamp: Instant,
        tokenDelta: Int
    ): Message

    suspend fun rename(id: String, title: String): Conversation?

    suspend fun clearMessagesAndResetTokens(id: String): Boolean

    suspend fun delete(id: String): Boolean
}
