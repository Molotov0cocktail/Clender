package com.molotov.clender.domain.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class AiSchedulingPromptContractTest {
    @Test
    fun mandatoryContractIncludesCompleteScheduleAndReplyInstructions() {
        val contract = requestContract()

        assertTrue(contract.contains("complete JSON"))
        assertTrue(contract.contains("message"))
        assertTrue(contract.contains("reminder"))
        assertTrue(contract.contains("timespan"))
        assertTrue(contract.contains("16"))
        assertTrue(contract.contains("Do not claim"))
    }

    @Test
    fun essentialScheduleInstructionsSurviveOptionalPromptTruncation() {
        val result = AiMessageBudgeter().build(
            AiBudgetInput(
                systemPrompt = "Optional style. ".repeat(1_000),
                personality = "Friendly. ".repeat(1_000),
                scheduleContext = "Synthetic event. ".repeat(1_000),
                history = emptyList(),
                contextWindow = 500,
                maxOutputTokens = 100
            )
        )
        val contract = result.messages.first().content

        assertTrue(contract.startsWith(DEFAULT_AI_SYSTEM_CONTRACT))
        assertTrue(contract.contains("complete JSON"))
        assertTrue(contract.contains("Do not claim"))
        assertTrue(result.inputTokenEstimate <= 350)
    }

    private fun requestContract(): String = AiMessageBudgeter().build(
        AiBudgetInput(
            systemPrompt = "",
            personality = "",
            scheduleContext = "",
            history = emptyList(),
            contextWindow = 500,
            maxOutputTokens = 100
        )
    ).messages.first().content
}
