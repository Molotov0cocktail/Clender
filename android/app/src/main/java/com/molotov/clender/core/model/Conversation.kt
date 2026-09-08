package com.molotov.clender.core.model

import java.time.Instant

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Instant,
    val tokenCount: Int,
    val lastInputTokenEstimate: Int? = null,
    val lastContextWindow: Int? = null
)

enum class MessageRole {
    USER,
    ASSISTANT,
    THINK
}

data class Message(
    val id: Long,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Instant
)
