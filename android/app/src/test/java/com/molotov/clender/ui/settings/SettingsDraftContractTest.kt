package com.molotov.clender.ui.settings

import com.molotov.clender.data.settings.ThinkingEffort
import com.molotov.clender.ui.foundation.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDraftContractTest {
    @Test
    fun defaultsAndBothInclusiveFontBoundariesAreValid() {
        val defaults = AppearanceSettingsDraft()
        assertTrue(defaults.validate().isEmpty())

        listOf(8, 20).forEach { boundary ->
            assertTrue(
                AppearanceSettingsDraft(
                    themeMode = ThemeMode.SYSTEM,
                    appFontSize = boundary.toString(),
                    widgetFontSize = boundary.toString()
                ).validate().isEmpty()
            )
        }
    }

    @Test
    fun applicationDraftRejectsOutOfRangeNonNumericAndOverflowSizes() {
        listOf("7", "21", "0", "-1", "not-a-number", "2147483648").forEach { raw ->
            assertTrue(
                AppearanceSettingsDraft(appFontSize = raw).validate()
                    .contains(SettingsValidationError.APP_FONT_SIZE)
            )
            assertTrue(
                AppearanceSettingsDraft(widgetFontSize = raw).validate()
                    .contains(SettingsValidationError.WIDGET_FONT_SIZE)
            )
        }
    }

    @Test
    fun aiDraftAcceptsNormalValuesAndPreservesUnicodeEmojiAndNewlines() {
        val prompt = "你是日历助手 🌏\n保留第二行"
        val personality = "冷静但友好 🙂\n不删空白"
        val draft = validAiDraft().copy(systemPrompt = prompt, personality = personality)

        assertTrue(draft.validate().isEmpty())
        assertEquals(prompt, draft.systemPrompt)
        assertEquals(personality, draft.personality)
    }

    @Test
    fun aiDraftRejectsEndpointTemperatureTokenAndContextEdgeCases() {
        listOf("http://provider.example/v1", "ftp://provider.example", "not a url").forEach {
            assertTrue(
                validAiDraft().copy(endpoint = it).validate().contains(
                    SettingsValidationError.AI_ENDPOINT
                )
            )
        }
        listOf("NaN", "Infinity", "-Infinity", "-0.1", "2.1", "text").forEach {
            assertTrue(
                validAiDraft().copy(temperature = it).validate().contains(
                    SettingsValidationError.AI_TEMPERATURE
                )
            )
        }
        listOf("0", "-1", "2147483648", "text").forEach {
            assertTrue(
                validAiDraft().copy(maxOutputTokens = it).validate().contains(
                    SettingsValidationError.AI_MAX_OUTPUT_TOKENS
                )
            )
            assertTrue(
                validAiDraft().copy(contextWindow = it).validate().contains(
                    SettingsValidationError.AI_CONTEXT_WINDOW
                )
            )
        }
        listOf("4096", "4095").forEach {
            assertTrue(
                validAiDraft().copy(contextWindow = it).validate().contains(
                    SettingsValidationError.AI_CONTEXT_WINDOW
                )
            )
        }
    }

    @Test
    fun aiTemperatureEndpointsAndTokenOrderingIncludeValidBoundaries() {
        listOf("0", "2", "0.7").forEach { temperature ->
            assertTrue(validAiDraft().copy(temperature = temperature).validate().isEmpty())
        }
        assertTrue(
            validAiDraft().copy(maxOutputTokens = "1", contextWindow = "2").validate().isEmpty()
        )
    }

    @Test
    fun secretInputNeverPrintsOrHydratesPersistedKeyAndCopiesCanBeWiped() {
        val raw = "ui-test-sentinel-key".toCharArray()
        val input = SecretInput.empty()
        input.replace(raw)
        raw.fill('\u0000')

        assertNotEquals("ui-test-sentinel-key", input.toString())
        assertFalse(input.toString().contains("sentinel"))
        val copy = input.copyChars()
        assertEquals("ui-test-sentinel-key", concat(copy))
        copy.fill('\u0000')
        input.clear()
        assertTrue(input.isEmpty())
        assertFalse(input.toString().contains("sentinel"))
    }

    @Test
    fun uiStateToStringIsSafeWhenReplacementInputExists() {
        val secret = SecretInput.from("never-print-this-key".toCharArray())
        val state = SettingsUiState(
            active = true,
            apiKeyConfigured = true,
            secretInput = secret
        )

        assertFalse(state.toString().contains("never-print-this-key"))
        secret.clear()
    }

    @Test
    fun configuredPresenceDoesNotCreateASecretInput() {
        val state = SettingsUiState(active = true, apiKeyConfigured = true)
        assertTrue(state.secretInput.isEmpty())
        assertTrue(state.apiKeyConfigured)
    }

    private fun validAiDraft() = AiSettingsDraft(
        endpoint = "https://provider.example/v1",
        model = "model-current",
        temperature = "0.7",
        maxOutputTokens = "4096",
        contextWindow = "128000",
        thinkingEnabled = true,
        thinkingEffort = ThinkingEffort.HIGH,
        systemPrompt = "system",
        personality = "personality"
    )

    private fun concat(chars: CharArray): String = buildString(chars.size) {
        chars.forEach(::append)
    }
}
