package com.molotov.clender.ui.settings

import com.molotov.clender.data.network.ai.AiModelCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelSettingsTest {
    @Test
    fun completeMetadataFillsBothValuesAndUnknownMetadataPreservesManualValues() {
        val draft = AiSettingsDraft(maxOutputTokens = "2000", contextWindow = "32000")
        val updated = draft.applyCapabilities(AiModelCapabilities(128000, 8192))
        assertEquals("128000", updated.contextWindow)
        assertEquals("8192", updated.maxOutputTokens)
        assertEquals(draft, draft.applyCapabilities(null))
        assertEquals(draft, draft.applyCapabilities(AiModelCapabilities()))
        val highOutput = draft.copy(maxOutputTokens = "30000")
        assertEquals(highOutput, highOutput.applyCapabilities(AiModelCapabilities()))
    }

    @Test
    fun incompatibleProviderOutputReservesInputAndSafetyAndTinyMetadataCannotBreakDraft() {
        val draft = AiSettingsDraft()
        val updated = draft.applyCapabilities(AiModelCapabilities(4096, 4096))
        assertTrue(updated.validate().isEmpty())
        assertEquals("2663", updated.maxOutputTokens)
        assertEquals(draft, draft.applyCapabilities(AiModelCapabilities(16, 16)))
        assertEquals(draft, draft.applyCapabilities(AiModelCapabilities(-1, -1)))
    }
}
