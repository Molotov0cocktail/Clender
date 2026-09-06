package com.molotov.clender.domain.conversation

import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRepositoryContractTest {
    @Test
    fun minimalRepositoryContractPreservesModelsAndMessageOrdering() = runSuspend {
        val repository: ConversationRepository = InMemoryConversationRepository()
        val conversation = Conversation("conversation-一", "计划 🌏", Instant.EPOCH, 0)

        assertEquals(conversation, repository.create(conversation))
        val message = repository.appendMessageAndIncrementTokens(
            conversation.id,
            MessageRole.USER,
            "第一条",
            Instant.parse("2026-08-07T01:00:00Z"),
            3
        )

        assertEquals("第一条", message.content)
        assertEquals(3, repository.findConversation(conversation.id)?.tokenCount)
        assertEquals(listOf(message), repository.observeMessages(conversation.id).first())
        assertTrue(repository.delete(conversation.id))
        assertEquals(null, repository.findConversation(conversation.id))
    }
}

private class InMemoryConversationRepository : ConversationRepository {
    private val conversations = linkedMapOf<String, Conversation>()
    private val messages = linkedMapOf<String, MutableList<Message>>()
    private var nextMessageId = 1L

    override suspend fun create(conversation: Conversation): Conversation {
        conversations[conversation.id] = conversation
        return conversation
    }

    override suspend fun findConversation(id: String): Conversation? = conversations[id]

    override suspend fun listConversations(): List<Conversation> = conversations.values.sortedWith(
        compareBy(Conversation::createdAt, Conversation::id)
    )

    override fun observeMessages(conversationId: String): Flow<List<Message>> = flowOf(
        messages[conversationId].orEmpty().sortedWith(
            compareBy(Message::timestamp, Message::id)
        )
    )

    override suspend fun appendMessageAndIncrementTokens(
        conversationId: String,
        role: MessageRole,
        content: String,
        timestamp: Instant,
        tokenDelta: Int
    ): Message {
        require(tokenDelta >= 0)
        val current = requireNotNull(conversations[conversationId])
        val message = Message(nextMessageId++, conversationId, role, content, timestamp)
        messages.getOrPut(conversationId, ::mutableListOf).add(message)
        conversations[conversationId] = current.copy(tokenCount = current.tokenCount + tokenDelta)
        return message
    }

    override suspend fun rename(id: String, title: String): Conversation? {
        val current = conversations[id] ?: return null
        val normalized = title.trim()
        require(normalized.isNotEmpty())
        return current.copy(title = normalized).also { conversations[id] = it }
    }

    override suspend fun clearMessagesAndResetTokens(id: String): Boolean {
        val current = conversations[id] ?: return false
        messages.remove(id)
        conversations[id] = current.copy(tokenCount = 0)
        return true
    }

    override suspend fun delete(id: String): Boolean {
        val removed = conversations.remove(id) ?: return false
        messages.remove(removed.id)
        return true
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        }
    )
    return requireNotNull(outcome) { "test block suspended unexpectedly" }.getOrThrow()
}
