package com.molotov.clender.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantMessagePresentationTest {
    @Test
    fun legacySafetyRejectionIsFeedbackButQuotedExplanationRemainsModelBody() {
        val rejection = "The assistant response could not be applied safely."
        assertEquals(
            AssistantMessagePresentation("", rejection),
            presentAssistantMessage(rejection)
        )
        val explanation = "旧错误提示是：$rejection 请检查格式。"
        assertEquals(
            AssistantMessagePresentation(explanation, null),
            presentAssistantMessage(explanation)
        )
        val continued = "$rejection This sentence explains the old error."
        assertEquals(
            AssistantMessagePresentation(continued, null),
            presentAssistantMessage(continued)
        )
    }

    @Test
    fun currentReceiptSeparatesActualFeedbackFromModelBody() {
        assertEquals(
            AssistantMessagePresentation("课程已安排。", "已实际完成 2 项日程操作。"),
            presentAssistantMessage("已实际完成 2 项日程操作。\n\n模型回复：\n课程已安排。")
        )
    }

    @Test
    fun onlyOutermostReceiptIsTrustedWhenOldModelBodyEchoesNestedReceipts() {
        assertEquals(
            AssistantMessagePresentation("请核对日期。", "本轮未修改日程。"),
            presentAssistantMessage(
                "No schedule changes were made. 本轮未修改日程。\n\n" +
                    "Assistant reply / 模型回复：\n" +
                    "4 schedule operation(s) completed. 已实际完成 4 项日程操作。\n\n" +
                    "Assistant reply / 模型回复：\n请核对日期。"
            )
        )
    }

    @Test
    fun receiptOnlyAndKnownRejectionMessagesHaveNoModelBody() {
        listOf(
            "本轮未修改日程。",
            "已实际完成 1 项日程操作，1 项未能执行。请先核对日历再重试。",
            "AI 请求未完成，本轮未修改日程。",
            "AI 返回的操作格式不符合要求，本轮未修改日程。请重试或拆分请求。"
        ).forEach { feedback ->
            assertEquals(
                AssistantMessagePresentation("", feedback),
                presentAssistantMessage(feedback)
            )
        }
    }

    @Test
    fun ordinaryTextAndReceiptWordsInsideBodyRemainUnchanged() {
        listOf(
            "你好，**课程**在周六。",
            "术语说明：模型回复：正文；本轮未修改日程。",
            "已实际完成 2 项日程操作。这里是普通连续正文。"
        ).forEach { body ->
            assertEquals(AssistantMessagePresentation(body, null), presentAssistantMessage(body))
        }
    }
}
