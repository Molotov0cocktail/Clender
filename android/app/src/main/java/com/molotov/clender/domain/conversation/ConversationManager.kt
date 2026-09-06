package com.molotov.clender.domain.conversation

import com.molotov.clender.core.model.Conversation
import java.time.Clock
import java.time.temporal.ChronoUnit

fun interface ConversationIdGenerator {
    fun generate(): String
}

class ConversationNotFoundException(id: String) :
    NoSuchElementException("Conversation $id was not found")

class ConversationIdCollisionException(id: String) :
    IllegalStateException("Conversation $id already exists")

class ConversationManager(
    private val repository: ConversationRepository,
    private val clock: Clock,
    private val idGenerator: ConversationIdGenerator,
    private val defaultTitle: String
) {
    init {
        require(defaultTitle.trim().isNotEmpty()) { "Default conversation title cannot be blank" }
    }

    suspend fun resolveActive(preferredId: String?): Conversation {
        preferredId?.takeIf(String::isNotBlank)?.let { id ->
            repository.findConversation(id)?.let { return it }
        }
        return repository.listConversations().firstOrNull() ?: create(defaultTitle)
    }

    suspend fun create(title: String = defaultTitle): Conversation {
        val normalizedTitle = title.trim()
        require(normalizedTitle.isNotEmpty()) { "Conversation title cannot be blank" }
        val id = idGenerator.generate()
        require(id.isNotBlank()) { "Generated conversation ID cannot be blank" }
        if (repository.findConversation(id) != null) throw ConversationIdCollisionException(id)
        return repository.create(
            Conversation(
                id = id,
                title = normalizedTitle,
                createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
                tokenCount = 0
            )
        )
    }

    suspend fun rename(id: String, title: String): Conversation {
        require(id.isNotBlank()) { "Conversation ID cannot be blank" }
        val normalizedTitle = title.trim()
        require(normalizedTitle.isNotEmpty()) { "Conversation title cannot be blank" }
        return repository.rename(id, normalizedTitle) ?: throw ConversationNotFoundException(id)
    }

    suspend fun clear(id: String) {
        require(id.isNotBlank()) { "Conversation ID cannot be blank" }
        if (!repository.clearMessagesAndResetTokens(id)) throw ConversationNotFoundException(id)
    }

    suspend fun deleteAndResolveActive(deletedId: String, activeId: String?): Conversation {
        require(deletedId.isNotBlank()) { "Conversation ID cannot be blank" }
        repository.delete(deletedId)
        activeId?.takeIf { it.isNotBlank() && it != deletedId }?.let { id ->
            repository.findConversation(id)?.let { return it }
        }
        return repository.listConversations().firstOrNull() ?: create(defaultTitle)
    }
}
