package com.molotov.clender.ui.ai

import com.molotov.clender.app.ai.AiContextUsage
import com.molotov.clender.app.ai.AiCoordinatorError
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiConversationFeedbackTest {
    private val completed = AiSubmissionUiState(
        isActive = true,
        status = AiSubmissionStatus.COMPLETED,
        requestConversationId = "a",
        requestUserMessageId = 1
    )
    private val receipt = "已实际完成 2 项日程操作。"
    private val messages = listOf(
        message(1, MessageRole.USER, "安排课程"),
        message(2, MessageRole.ASSISTANT, "$receipt\n\n模型回复：\n课程已安排。")
    )

    @Test
    fun persistedContextSurvivesSwitchingAndNeverUsesCumulativeProviderTokensAsInput() {
        val saved = Conversation("a", "title", Instant.EPOCH, 999999, 2000, 8000)
        val expected = AiContextUsage("a", 2000, 8000)
        assertEquals(expected, conversationContextUsage(saved))
        assertEquals(
            AiContextUsage("a", 3000, 8000),
            conversationContextUsage(saved.copy(lastInputTokenEstimate = 3000))
        )
        assertNull(conversationContextUsage(saved.copy(lastInputTokenEstimate = null)))
        assertNull(conversationContextUsage(saved.copy(lastContextWindow = 0)))
        assertNull(
            conversationContextUsage(
                saved.copy(lastInputTokenEstimate = null, lastContextWindow = null)
            )
        )
    }

    @Test
    fun completedFeedbackAppearsWhenCurrentConversationMessageFlowArrivesLate() {
        assertNull(currentExecutionFeedback(messages.take(1), completed, "a"))
        assertEquals(receipt, currentExecutionFeedback(messages, completed, "a"))
    }

    @Test
    fun workingDismissedPreflightAndDifferentConversationNeverShowOldReceipt() {
        listOf(AiSubmissionStatus.WORKING, AiSubmissionStatus.IDLE).forEach { status ->
            assertNull(currentExecutionFeedback(messages, completed.copy(status = status), "a"))
        }
        assertNull(currentExecutionFeedback(messages, completed, "b"))
        val preflight = completed.copy(
            status = AiSubmissionStatus.FAILED,
            errorCode = AiCoordinatorError.CONFIGURATION,
            requestConversationId = null
        )
        assertNull(currentExecutionFeedback(messages, preflight, "a"))
        assertEquals(
            AiSubmissionStatus.IDLE,
            conversationSubmissionState(completed, "b").status
        )
        assertEquals(
            AiSubmissionStatus.WORKING,
            conversationSubmissionState(
                completed.copy(status = AiSubmissionStatus.WORKING),
                "b"
            ).status
        )
    }

    @Test
    fun newUserAndNetworkFailureDoNotReusePriorSuccess() {
        val failed = completed.copy(
            status = AiSubmissionStatus.FAILED,
            errorCode = AiCoordinatorError.NETWORK,
            requestUserMessageId = 3
        )
        assertNull(
            currentExecutionFeedback(messages + message(3, MessageRole.USER, "再安排"), failed, "a")
        )
    }

    @Test
    fun secondRequestTerminalBeforeMessageFlowNeverShowsFirstRequestReceipt() {
        val second = completed.copy(requestUserMessageId = 3)
        assertNull(currentExecutionFeedback(messages, second, "a"))
        val pending = messages + message(3, MessageRole.USER, "再安排")
        assertNull(currentExecutionFeedback(pending, second, "a"))
        val nextReceipt = "已实际完成 1 项日程操作。"
        val arrived = pending + message(4, MessageRole.ASSISTANT, nextReceipt)
        assertEquals(nextReceipt, currentExecutionFeedback(arrived, second, "a"))
    }

    @Test
    fun missingRequestMessageIdentityUsesGenericStatusInsteadOfInferringOldReceipt() {
        assertNull(
            currentExecutionFeedback(messages, completed.copy(requestUserMessageId = null), "a")
        )
    }

    @Test
    fun parserRejectionFeedbackIsShownWithoutInventingModelBody() {
        val error = "AI 返回的操作格式不符合要求，本轮未修改日程。请重试或拆分请求。"
        val rejected = messages.take(1) + message(2, MessageRole.ASSISTANT, error)
        assertEquals(
            error,
            currentExecutionFeedback(
                rejected,
                completed.copy(
                    status = AiSubmissionStatus.FAILED,
                    errorCode = AiCoordinatorError.INVALID_RESPONSE
                ),
                "a"
            )
        )
    }

    private fun message(id: Long, role: MessageRole, body: String) =
        Message(id, "a", role, body, Instant.ofEpochSecond(id))
}
