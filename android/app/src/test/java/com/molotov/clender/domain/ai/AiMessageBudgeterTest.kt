package com.molotov.clender.domain.ai

import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMessageBudgeterTest {
    private val budgeter = AiMessageBudgeter()

    @Test
    fun tokenEstimateIsConservativeOverUtf8BytesAndIncludesMessageOverhead() {
        assertEquals(0, budgeter.estimateTokens(""))
        assertEquals(1, budgeter.estimateTokens("abc"))
        assertEquals(1, budgeter.estimateTokens("中"))
        assertEquals(2, budgeter.estimateTokens("🌏"))
        assertTrue(
            budgeter.countTokens(listOf(AiRequestMessage("user", "abc"))) >=
                budgeter.estimateTokens("abc") + AiMessageBudgeter.MESSAGE_OVERHEAD_TOKENS
        )
    }

    @Test
    fun buildPreservesSystemContextNewestHistoryAndDropsThinkingWithinBudget() {
        val history = (1..20).map { index ->
            Message(
                id = index.toLong(),
                conversationId = "conversation-a",
                role = when {
                    index == 10 -> MessageRole.THINK
                    index % 2 == 0 -> MessageRole.ASSISTANT
                    else -> MessageRole.USER
                },
                content = "message-$index-${"内容".repeat(30)}",
                timestamp = Instant.EPOCH.plusSeconds(index.toLong())
            )
        }
        val result = budgeter.build(
            AiBudgetInput(
                systemPrompt = "system contract",
                personality = "patient",
                scheduleContext = "date and sanitized event summaries",
                history = history,
                contextWindow = 500,
                maxOutputTokens = 100
            )
        )

        assertEquals("system", result.messages.first().role)
        assertTrue(result.messages.first().content.contains("system contract"))
        assertFalse(result.messages.any { it.role == "think" })
        assertTrue(result.messages.last().content.endsWith("内容".repeat(30)))
        assertTrue(result.inputTokenEstimate <= 350)
        assertEquals(maxOf(32, 500 / 10), result.safetyMarginTokens)
    }

    @Test
    fun truncationNeverSplitsUnicodeSurrogatePairsAndKeepsNewestTail() {
        val newest = "prefix-${"🌏".repeat(200)}-tail"
        val result = budgeter.build(
            AiBudgetInput(
                mandatorySystemContract = "contract",
                systemPrompt = "system",
                personality = "",
                scheduleContext = "",
                history = listOf(
                    Message(1, "c", MessageRole.USER, newest, Instant.EPOCH)
                ),
                contextWindow = 180,
                maxOutputTokens = 64
            )
        )
        val retained = result.messages.last().content

        assertTrue(retained.endsWith("-tail"))
        assertFalse(retained.first().isLowSurrogate())
        assertFalse(retained.last().isHighSurrogate())
        assertTrue(result.inputTokenEstimate <= 84)
    }

    @Test
    fun invalidOrImpossibleBudgetsFailClosed() {
        listOf(
            0 to 1,
            100 to 0,
            100 to 100,
            100 to 101
        ).forEach { (contextWindow, output) ->
            assertThrows(IllegalArgumentException::class.java) {
                budgeter.build(
                    AiBudgetInput(
                        systemPrompt = "system",
                        personality = "",
                        scheduleContext = "",
                        history = emptyList(),
                        contextWindow = contextWindow,
                        maxOutputTokens = output
                    )
                )
            }
        }
    }

    @Test
    fun oversizedSystemContextIsSafelyTruncatedWithinARequiredSystemMessage() {
        val result = budgeter.build(
            AiBudgetInput(
                mandatorySystemContract = "contract-core",
                systemPrompt = "contract-${"x".repeat(10_000)}",
                personality = "personality-${"y".repeat(10_000)}",
                scheduleContext = "schedule-${"z".repeat(10_000)}-tail",
                history = emptyList(),
                contextWindow = 256,
                maxOutputTokens = 64
            )
        )

        assertEquals("system", result.messages.single().role)
        assertTrue(result.messages.single().content.startsWith("contract-core"))
        assertTrue(result.messages.single().content.endsWith("-tail"))
        assertTrue(result.inputTokenEstimate <= 160)
    }

    @Test
    fun mandatoryContractIsNeverTruncatedForHugeOptionalContextAndTinyBudgetStaysBounded() {
        val optionalHuge = "optional-${"x".repeat(20_000)}"
        val result = budgeter.build(
            AiBudgetInput(
                mandatorySystemContract = DEFAULT_AI_SYSTEM_CONTRACT,
                systemPrompt = optionalHuge,
                personality = optionalHuge,
                scheduleContext = optionalHuge,
                history = emptyList(),
                contextWindow = 512,
                maxOutputTokens = 64
            )
        )
        assertTrue(result.messages.single().content.startsWith(DEFAULT_AI_SYSTEM_CONTRACT))
        assertTrue(result.inputTokenEstimate <= 397)

        val tiny = budgeter.build(
            AiBudgetInput(
                mandatorySystemContract = "x",
                systemPrompt = optionalHuge,
                personality = optionalHuge,
                scheduleContext = optionalHuge,
                history = emptyList(),
                contextWindow = 38,
                maxOutputTokens = 1
            )
        )
        assertEquals("x", tiny.messages.single().content)
        assertTrue(tiny.inputTokenEstimate <= 5)
    }
}
