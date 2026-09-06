package com.molotov.clender.data.settings

import androidx.datastore.preferences.core.MutablePreferences

internal fun validateAppearance(appearance: AppearanceSettings) {
    require(appearance.appFontSizeSp in FONT_RANGE) { "App font size is out of range" }
    require(appearance.widgetFontSizeSp in FONT_RANGE) { "Widget font size is out of range" }
}

internal fun validateAi(settings: AiSettings) {
    require(
        settings.temperature.isFinite() && settings.temperature in 0.0..MAX_AI_TEMPERATURE
    ) {
        "AI temperature is out of range"
    }
    require(settings.maxOutputTokens > 0) { "AI output budget must be positive" }
    require(settings.contextWindow > settings.maxOutputTokens) {
        "AI context window must exceed output budget"
    }
    if (settings.endpoint.isNotBlank()) {
        require(isValidAiEndpoint(settings.endpoint)) { "AI endpoint must use HTTPS" }
    }
}

internal fun encodeAi(values: MutablePreferences, settings: AiSettings) {
    values[PreferenceWireCodec.string(AppPreferenceKeys.AI_ENDPOINT)] = settings.endpoint
    values[PreferenceWireCodec.string(AppPreferenceKeys.AI_MODEL)] = settings.model
    values[PreferenceWireCodec.string(AppPreferenceKeys.AI_TEMPERATURE)] =
        settings.temperature.toString()
    values[PreferenceWireCodec.string(AppPreferenceKeys.AI_MAX_OUTPUT_TOKENS)] =
        settings.maxOutputTokens.toString()
    values[PreferenceWireCodec.string(AppPreferenceKeys.AI_CONTEXT_WINDOW)] =
        settings.contextWindow.toString()
    values[PreferenceWireCodec.string(AppPreferenceKeys.AI_THINKING_ENABLED)] =
        settings.thinkingEnabled.toString()
    values[PreferenceWireCodec.string(AppPreferenceKeys.AI_THINKING_EFFORT)] =
        settings.thinkingEffort.name
    values[PreferenceWireCodec.string(AppPreferenceKeys.AI_SYSTEM_PROMPT)] = settings.systemPrompt
    values[PreferenceWireCodec.string(AppPreferenceKeys.AI_PERSONALITY)] = settings.personality
}

internal fun isValidAiEndpoint(raw: String): Boolean =
    runCatching { AiEndpointPolicy.resolve(raw, "models") }.isSuccess

private val FONT_RANGE = MIN_FONT_SIZE..MAX_FONT_SIZE
private const val MIN_FONT_SIZE = 8
private const val MAX_FONT_SIZE = 20
internal const val MAX_AI_TEMPERATURE = 2.0
