package com.molotov.clender.ui.settings

import com.molotov.clender.data.network.ai.AiModelCapabilities
import com.molotov.clender.domain.ai.AiBudgetInput
import com.molotov.clender.domain.ai.AiMessageBudgeter
import org.junit.Assert.assertTrue
import org.junit.Test

class T80AiBudgetValidationTest {
    @Test
    fun unusableProviderContextIsShownInsteadOfRetainingThePreviousModelsLargeWindow() {
        val filled = AiSettingsDraft().applyCapabilities(AiModelCapabilities(16, 16))
        assertTrue(filled.contextWindow == "16")
        assertTrue(SettingsValidationError.AI_CONTEXT_WINDOW in filled.validate())
    }

    @Test
    fun automaticallyFilledLimitsLeaveSpaceForTheActualFixedContract() {
        val filled = AiSettingsDraft().applyCapabilities(AiModelCapabilities(4096, 4096))
        val request = AiMessageBudgeter().build(
            AiBudgetInput(
                systemPrompt = "",
                personality = "",
                scheduleContext = "",
                history = emptyList(),
                contextWindow = filled.contextWindow.toInt(),
                maxOutputTokens = filled.maxOutputTokens.toInt()
            )
        )
        assertTrue(request.inputTokenEstimate > 0)
        assertTrue(filled.validate().isEmpty())
    }

    @Test
    fun outputBelowContextStillRequiresSpaceForTheFixedContract() {
        val errors = AiSettingsDraft(maxOutputTokens = "8191", contextWindow = "8192").validate()
        assertTrue(SettingsValidationError.AI_CONTEXT_WINDOW in errors)
    }

    @Test
    fun unusablySmallContextCannotBeSaved() {
        val errors = AiSettingsDraft(maxOutputTokens = "1", contextWindow = "2").validate()
        assertTrue(SettingsValidationError.AI_CONTEXT_WINDOW in errors)
    }

    @Test
    fun normalAndLargeManuallySpecifiedBudgetsRemainSupported() {
        assertTrue(AiSettingsDraft().validate().isEmpty())
        assertTrue(
            AiSettingsDraft(maxOutputTokens = "131072", contextWindow = "1048576")
                .validate().isEmpty()
        )
    }
}
