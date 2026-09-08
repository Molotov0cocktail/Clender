package com.molotov.clender.ui.ai

import com.molotov.clender.app.ai.AiContextUsage
import com.molotov.clender.app.ai.presentAssistantMessage
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole

internal fun conversationSubmissionState(
    state: AiSubmissionUiState,
    conversationId: String
): AiSubmissionUiState = if (
    state.status != AiSubmissionStatus.WORKING &&
    state.requestConversationId != null && state.requestConversationId != conversationId
) {
    state.copy(status = AiSubmissionStatus.IDLE, errorCode = null)
} else {
    state
}

internal fun currentExecutionFeedback(
    messages: List<Message>,
    state: AiSubmissionUiState,
    conversationId: String
): String? {
    val userId = state.requestUserMessageId
    if (state.requestConversationId != conversationId ||
        state.status !in setOf(AiSubmissionStatus.COMPLETED, AiSubmissionStatus.FAILED) ||
        userId == null
    ) {
        return null
    }
    val ordered = messages.filter { it.conversationId == conversationId }
        .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
    val userIndex = ordered.indexOfFirst { it.id == userId && it.role == MessageRole.USER }
    return if (userIndex >= 0) {
        ordered.drop(userIndex + 1).takeWhile { it.role != MessageRole.USER }
            .firstOrNull { it.role == MessageRole.ASSISTANT }
            ?.let { presentAssistantMessage(it.content).executionFeedback }
    } else {
        null
    }
}

internal fun conversationContextUsage(conversation: Conversation): AiContextUsage? {
    val input = conversation.lastInputTokenEstimate
    val window = conversation.lastContextWindow
    return if (input != null && window != null) {
        AiContextUsage(conversation.id, input, window).takeIf { it.isValid() }
    } else {
        null
    }
}

private fun AiContextUsage.isValid(): Boolean = contextWindow > 0 && inputTokens in 0..contextWindow
