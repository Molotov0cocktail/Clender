package com.molotov.clender.domain.conversation

import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationManagerTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-09T01:02:03Z"), ZoneOffset.UTC)

    @Test
    fun emptyRepositoryCreatesExactlyOneDefaultActiveConversation() = runBlocking {
        val repository = ManagedMemoryConversationRepository()
        val manager = manager(repository)

        val active = manager.resolveActive(null)

        assertEquals("conversation-1", active.id)
        assertEquals("新对话", active.title)
        assertEquals(listOf(active), repository.listConversations())
        assertEquals(active, manager.resolveActive(active.id))
        assertEquals(1, repository.listConversations().size)
    }

    @Test
    fun deletingLastConversationImmediatelyCreatesReplacement() = runBlocking {
        val repository = ManagedMemoryConversationRepository()
        val manager = manager(repository)
        val only = manager.resolveActive(null)

        val replacement = manager.deleteAndResolveActive(only.id, only.id)

        assertEquals("conversation-2", replacement.id)
        assertEquals("新对话", replacement.title)
        assertNull(repository.findConversation(only.id))
        assertEquals(listOf(replacement), repository.listConversations())
    }

    @Test
    fun deletingActiveChoosesDeterministicNextAndDeletingInactiveKeepsActive() = runBlocking {
        val repository = ManagedMemoryConversationRepository()
        val manager = manager(repository)
        val first = manager.create("一")
        val second = manager.create("二")
        val third = manager.create("三")

        assertEquals(second, manager.deleteAndResolveActive(first.id, first.id))
        assertEquals(second, manager.deleteAndResolveActive(third.id, second.id))
        assertEquals(listOf(second), repository.listConversations())
    }

    @Test
    fun missingPreferredIdResolvesExistingFirstWithoutCreatingAnother() = runBlocking {
        val repository = ManagedMemoryConversationRepository()
        val manager = manager(repository)
        val first = manager.create("一")
        manager.create("二")

        assertEquals(first, manager.resolveActive("missing"))
        assertEquals(2, repository.listConversations().size)
        assertEquals(first, manager.deleteAndResolveActive("missing", first.id))
        assertEquals(2, repository.listConversations().size)
    }

    @Test
    fun renameAndClearValidateInputAndResetMessagesWithTokenCount(): Unit = runBlocking {
        val repository = ManagedMemoryConversationRepository()
        val manager = manager(repository)
        val conversation = manager.create("原名")
        repository.appendMessageAndIncrementTokens(
            conversation.id,
            MessageRole.USER,
            "内容 🌏",
            clock.instant(),
            17
        )

        val renamed = manager.rename(conversation.id, "  新名  ")
        manager.clear(conversation.id)

        assertEquals("新名", renamed.title)
        assertEquals(0, repository.findConversation(conversation.id)?.tokenCount)
        assertTrue(repository.observeMessages(conversation.id).first().isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { manager.rename(conversation.id, " \t") }
        }
        assertThrows(ConversationNotFoundException::class.java) {
            runBlocking { manager.rename("missing", "name") }
        }
        assertThrows(ConversationNotFoundException::class.java) {
            runBlocking { manager.clear("missing") }
        }
    }

    @Test
    fun blankTitleAndIdCollisionDoNotOverwriteExisting() = runBlocking {
        val repository = ManagedMemoryConversationRepository()
        val existing = Conversation("duplicate", "existing", clock.instant(), 0)
        repository.create(existing)
        val manager = ConversationManager(
            repository = repository,
            clock = clock,
            idGenerator = ConversationIdGenerator { "duplicate" },
            defaultTitle = "新对话"
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { manager.create(" \t") }
        }
        assertThrows(ConversationIdCollisionException::class.java) {
            runBlocking { manager.create("new") }
        }
        assertEquals(existing, repository.findConversation(existing.id))
        assertEquals(1, repository.listConversations().size)
    }

    private fun manager(repository: ManagedMemoryConversationRepository) = ConversationManager(
        repository = repository,
        clock = clock,
        idGenerator = ConversationIdGenerator {
            "conversation-${repository.generatedIdCounter++}"
        },
        defaultTitle = "新对话"
    )
}

private class ManagedMemoryConversationRepository : ConversationRepository {
    private val conversations = linkedMapOf<String, Conversation>()
    private val messages = linkedMapOf<String, MutableList<Message>>()
    private var nextMessageId = 1L
    var generatedIdCounter = 1

    override suspend fun create(conversation: Conversation): Conversation {
        check(conversations.putIfAbsent(conversation.id, conversation) == null)
        return conversation
    }

    override suspend fun findConversation(id: String): Conversation? = conversations[id]

    override suspend fun listConversations(): List<Conversation> = conversations.values.sortedWith(
        compareBy(Conversation::createdAt, Conversation::id)
    )

    override fun observeMessages(conversationId: String): Flow<List<Message>> = flowOf(
        messages[conversationId].orEmpty().sortedWith(compareBy(Message::timestamp, Message::id))
    )

    override suspend fun appendMessageAndIncrementTokens(
        conversationId: String,
        role: MessageRole,
        content: String,
        timestamp: Instant,
        tokenDelta: Int
    ): Message {
        val current = requireNotNull(conversations[conversationId])
        val message = Message(nextMessageId++, conversationId, role, content, timestamp)
        messages.getOrPut(conversationId, ::mutableListOf).add(message)
        conversations[conversationId] = current.copy(tokenCount = current.tokenCount + tokenDelta)
        return message
    }

    override suspend fun rename(id: String, title: String): Conversation? {
        val current = conversations[id] ?: return null
        return current.copy(title = title).also { conversations[id] = it }
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
