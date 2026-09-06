package com.molotov.clender.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import java.time.DateTimeException
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class ThinkingEffort {
    LOW,
    MEDIUM,
    HIGH,
    MAX
}

data class AiSettings(
    val endpoint: String,
    val model: String,
    val temperature: Double,
    val maxOutputTokens: Int,
    val contextWindow: Int,
    val thinkingEnabled: Boolean,
    val thinkingEffort: ThinkingEffort,
    val systemPrompt: String,
    val personality: String
)

data class AppearanceSettings(
    val theme: ThemeMode,
    val appFontSizeSp: Int,
    val widgetFontSizeSp: Int
)

sealed interface ApiKeyMutation {
    data object Keep : ApiKeyMutation

    class Replace(val value: CharArray) : ApiKeyMutation

    data object Remove : ApiKeyMutation
}

data class WebDavPreferences(val enabled: Boolean, val url: String, val username: String)

enum class TopDestinationPreference {
    CALENDAR,
    EVENTS,
    AI,
    SETTINGS,
    ABOUT
}

enum class CalendarModePreference {
    MONTH,
    WEEK,
    DAY
}

data class NavigationPreferences(
    val topDestination: TopDestinationPreference = TopDestinationPreference.CALENDAR,
    val selectedDate: LocalDate? = null,
    val calendarMode: CalendarModePreference = CalendarModePreference.MONTH
)

data class AppPreferencesState(
    val theme: ThemeMode,
    val appFontSizeSp: Int,
    val widgetFontSizeSp: Int,
    val activeConversationId: String?,
    val webDav: WebDavPreferences,
    val ai: AiSettings,
    val navigation: NavigationPreferences = NavigationPreferences()
) {
    companion object {
        val DEFAULT = AppPreferencesState(
            theme = ThemeMode.SYSTEM,
            appFontSizeSp = 13,
            widgetFontSizeSp = 13,
            activeConversationId = null,
            webDav = WebDavPreferences(false, "", ""),
            ai = AiSettings(
                endpoint = "",
                model = "",
                temperature = 0.7,
                maxOutputTokens = 4_096,
                contextWindow = 128_000,
                thinkingEnabled = true,
                thinkingEffort = ThinkingEffort.HIGH,
                systemPrompt = "",
                personality = ""
            )
        )
    }
}

object AppPreferenceKeys {
    const val THEME = "theme"
    const val APP_FONT_SIZE_SP = "app_font_size_sp"
    const val WIDGET_FONT_SIZE_SP = "widget_font_size_sp"
    const val ACTIVE_CONVERSATION_ID = "active_conversation_id"
    const val WEB_DAV_ENABLED = "web_dav_enabled"
    const val WEB_DAV_URL = "web_dav_url"
    const val WEB_DAV_USERNAME = "web_dav_username"
    const val AI_ENDPOINT = "ai_endpoint"
    const val AI_MODEL = "ai_model"
    const val AI_TEMPERATURE = "ai_temperature"
    const val AI_MAX_OUTPUT_TOKENS = "ai_max_output_tokens"
    const val AI_CONTEXT_WINDOW = "ai_context_window"
    const val AI_THINKING_ENABLED = "ai_thinking_enabled"
    const val AI_THINKING_EFFORT = "ai_thinking_effort"
    const val AI_SYSTEM_PROMPT = "ai_system_prompt"
    const val AI_PERSONALITY = "ai_personality"
    const val LAST_TOP_DESTINATION = "last_top_destination"
    const val SELECTED_DATE = "selected_date"
    const val CALENDAR_MODE = "calendar_mode"
}

class DataStoreAppPreferences(private val dataStore: DataStore<Preferences>) {
    val state: Flow<AppPreferencesState> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::decodeState)

    val appearance: Flow<AppearanceSettings> = state.map { preferences ->
        AppearanceSettings(
            theme = preferences.theme,
            appFontSizeSp = preferences.appFontSizeSp,
            widgetFontSizeSp = preferences.widgetFontSizeSp
        )
    }

    suspend fun save(state: AppPreferencesState) {
        validate(state)
        dataStore.edit { values ->
            values[key(AppPreferenceKeys.THEME)] = state.theme.name
            values[key(AppPreferenceKeys.APP_FONT_SIZE_SP)] = state.appFontSizeSp.toString()
            values[key(AppPreferenceKeys.WIDGET_FONT_SIZE_SP)] = state.widgetFontSizeSp.toString()
            setOrRemove(
                values,
                AppPreferenceKeys.ACTIVE_CONVERSATION_ID,
                state.activeConversationId
            )
            values[key(AppPreferenceKeys.WEB_DAV_ENABLED)] = state.webDav.enabled.toString()
            values[key(AppPreferenceKeys.WEB_DAV_URL)] = state.webDav.url
            values[key(AppPreferenceKeys.WEB_DAV_USERNAME)] = state.webDav.username
            values[key(AppPreferenceKeys.AI_ENDPOINT)] = state.ai.endpoint
            values[key(AppPreferenceKeys.AI_MODEL)] = state.ai.model
            values[key(AppPreferenceKeys.AI_TEMPERATURE)] = state.ai.temperature.toString()
            values[key(AppPreferenceKeys.AI_MAX_OUTPUT_TOKENS)] =
                state.ai.maxOutputTokens.toString()
            values[key(AppPreferenceKeys.AI_CONTEXT_WINDOW)] = state.ai.contextWindow.toString()
            values[key(AppPreferenceKeys.AI_THINKING_ENABLED)] =
                state.ai.thinkingEnabled.toString()
            values[key(AppPreferenceKeys.AI_THINKING_EFFORT)] = state.ai.thinkingEffort.name
            values[key(AppPreferenceKeys.AI_SYSTEM_PROMPT)] = state.ai.systemPrompt
            values[key(AppPreferenceKeys.AI_PERSONALITY)] = state.ai.personality
            values[key(AppPreferenceKeys.LAST_TOP_DESTINATION)] =
                state.navigation.topDestination.name
            setOrRemove(
                values,
                AppPreferenceKeys.SELECTED_DATE,
                state.navigation.selectedDate?.toString()
            )
            values[key(AppPreferenceKeys.CALENDAR_MODE)] = state.navigation.calendarMode.name
        }
    }

    suspend fun updateAppearance(appearance: AppearanceSettings) {
        validateAppearance(appearance)
        dataStore.edit { values ->
            values[key(AppPreferenceKeys.THEME)] = appearance.theme.name
            values[key(AppPreferenceKeys.APP_FONT_SIZE_SP)] =
                appearance.appFontSizeSp.toString()
            values[key(AppPreferenceKeys.WIDGET_FONT_SIZE_SP)] =
                appearance.widgetFontSizeSp.toString()
        }
    }

    suspend fun updateAi(
        settings: AiSettings,
        apiKeyMutation: ApiKeyMutation,
        cipher: SecretCipher
    ) {
        val replacement = (apiKeyMutation as? ApiKeyMutation.Replace)?.value
        try {
            validateAi(settings)
            val effectiveMutation =
                if (
                    apiKeyMutation is ApiKeyMutation.Replace &&
                    apiKeyMutation.value.all(Char::isWhitespace)
                ) {
                    ApiKeyMutation.Keep
                } else {
                    apiKeyMutation
                }
            val replacementEnvelope = when (effectiveMutation) {
                ApiKeyMutation.Keep,
                ApiKeyMutation.Remove -> null

                is ApiKeyMutation.Replace -> cipher.encrypt(
                    SecretAlias.AI_API_KEY,
                    effectiveMutation.value
                )
            }
            val envelopeKeys = PreferenceWireCodec.envelope(SecretAlias.AI_API_KEY)
            dataStore.edit { values ->
                encodeAi(values, settings)
                when (effectiveMutation) {
                    ApiKeyMutation.Keep -> Unit

                    ApiKeyMutation.Remove -> {
                        values.remove(envelopeKeys.version)
                        values.remove(envelopeKeys.iv)
                        values.remove(envelopeKeys.ciphertext)
                    }

                    is ApiKeyMutation.Replace -> {
                        val envelope = requireNotNull(replacementEnvelope)
                        values[envelopeKeys.version] = envelope.version
                        values[envelopeKeys.iv] = envelope.iv
                        values[envelopeKeys.ciphertext] = envelope.ciphertext
                    }
                }
            }
        } finally {
            replacement?.fill('\u0000')
        }
    }

    suspend fun updateWebDav(
        settings: WebDavPreferences,
        mutation: WebDavPasswordMutation,
        cipher: SecretCipher
    ): WebDavSectionSnapshot {
        val replacement = (mutation as? WebDavPasswordMutation.Replace)?.value
        try {
            val validated = validateWebDavDraftOrNull(settings)
            val effectiveMutation = mutation.normalizeBlankReplace()
            val passwordConfigured = effectivePasswordConfigured(effectiveMutation)
            require(!settings.enabled || passwordConfigured) {
                "WebDAV password must be configured when enabled"
            }
            val replacementEnvelope = effectiveMutation.encryptIfReplacement(cipher)
            val persistedUrl =
                if (validated == null) settings.url.trim() else validated.directoryUrl
            val persistedUsername =
                if (validated == null) settings.username.trim() else validated.username
            val envelopeKeys = PreferenceWireCodec.envelope(SecretAlias.WEB_DAV_PASSWORD)
            dataStore.edit { values ->
                values[key(AppPreferenceKeys.WEB_DAV_ENABLED)] = settings.enabled.toString()
                values[key(AppPreferenceKeys.WEB_DAV_URL)] = persistedUrl
                values[key(AppPreferenceKeys.WEB_DAV_USERNAME)] = persistedUsername
                writeWebDavEnvelope(values, envelopeKeys, effectiveMutation, replacementEnvelope)
            }
            return WebDavSectionSnapshot(
                enabled = settings.enabled,
                url = persistedUrl,
                username = persistedUsername,
                passwordConfigured = passwordConfigured
            )
        } finally {
            replacement?.fill('\u0000')
        }
    }

    private suspend fun effectivePasswordConfigured(mutation: WebDavPasswordMutation): Boolean =
        when (mutation) {
            WebDavPasswordMutation.Keep -> webDavEnvelopeConfigured()
            WebDavPasswordMutation.Remove -> false
            is WebDavPasswordMutation.Replace -> true
        }

    private suspend fun webDavEnvelopeConfigured(): Boolean {
        val values = dataStore.data.first()
        val keys = PreferenceWireCodec.envelope(SecretAlias.WEB_DAV_PASSWORD)
        return values[keys.version] != null &&
            values[keys.iv] != null &&
            values[keys.ciphertext] != null
    }

    private fun decodeState(values: Preferences): AppPreferencesState {
        val defaults = AppPreferencesState.DEFAULT
        val webDav = decodeWebDav(values, defaults.webDav)
        return AppPreferencesState(
            theme = enumOrDefault(values[AppPreferenceKeys.THEME], defaults.theme),
            appFontSizeSp = intInRangeOrDefault(
                values[AppPreferenceKeys.APP_FONT_SIZE_SP],
                FONT_RANGE,
                defaults.appFontSizeSp
            ),
            widgetFontSizeSp = intInRangeOrDefault(
                values[AppPreferenceKeys.WIDGET_FONT_SIZE_SP],
                FONT_RANGE,
                defaults.widgetFontSizeSp
            ),
            activeConversationId = values[AppPreferenceKeys.ACTIVE_CONVERSATION_ID]
                ?.trim()?.takeIf(String::isNotEmpty),
            webDav = webDav,
            ai = decodeAi(values, defaults.ai),
            navigation = NavigationPreferences(
                topDestination = enumOrDefault(
                    values[AppPreferenceKeys.LAST_TOP_DESTINATION],
                    defaults.navigation.topDestination
                ),
                selectedDate = strictLocalDateOrNull(values[AppPreferenceKeys.SELECTED_DATE]),
                calendarMode = enumOrDefault(
                    values[AppPreferenceKeys.CALENDAR_MODE],
                    defaults.navigation.calendarMode
                )
            )
        )
    }

    private fun decodeWebDav(values: Preferences, defaults: WebDavPreferences): WebDavPreferences {
        val enabled = strictBoolean(values[AppPreferenceKeys.WEB_DAV_ENABLED])
            ?: defaults.enabled
        val url = values[AppPreferenceKeys.WEB_DAV_URL].orEmpty().trim()
        val username = values[AppPreferenceKeys.WEB_DAV_USERNAME].orEmpty().trim()
        val hasValues = url.isNotEmpty() || username.isNotEmpty()
        val valid = (!enabled && !hasValues) || isValidWebDavSettings(url, username)
        return if (valid) {
            WebDavPreferences(enabled, url, username)
        } else {
            defaults
        }
    }

    private fun decodeAi(values: Preferences, defaults: AiSettings): AiSettings {
        val temperature = values[AppPreferenceKeys.AI_TEMPERATURE]?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 0.0..MAX_AI_TEMPERATURE }
            ?: defaults.temperature
        val candidateMaxOutput = positiveIntOrDefault(
            values[AppPreferenceKeys.AI_MAX_OUTPUT_TOKENS],
            defaults.maxOutputTokens
        )
        val candidateContextWindow = positiveIntOrDefault(
            values[AppPreferenceKeys.AI_CONTEXT_WINDOW],
            defaults.contextWindow
        )
        val (maxOutput, contextWindow) = if (candidateContextWindow > candidateMaxOutput) {
            candidateMaxOutput to candidateContextWindow
        } else {
            defaults.maxOutputTokens to defaults.contextWindow
        }
        val rawEndpoint = values[AppPreferenceKeys.AI_ENDPOINT].orEmpty().trim()
        val endpoint = rawEndpoint.takeIf { it.isEmpty() || isValidAiEndpoint(it) }.orEmpty()
        return AiSettings(
            endpoint = endpoint,
            model = values[AppPreferenceKeys.AI_MODEL].orEmpty().trim(),
            temperature = temperature,
            maxOutputTokens = maxOutput,
            contextWindow = contextWindow,
            thinkingEnabled = strictBoolean(values[AppPreferenceKeys.AI_THINKING_ENABLED])
                ?: defaults.thinkingEnabled,
            thinkingEffort = enumOrDefault(
                values[AppPreferenceKeys.AI_THINKING_EFFORT],
                defaults.thinkingEffort
            ),
            systemPrompt = values[AppPreferenceKeys.AI_SYSTEM_PROMPT].orEmpty(),
            personality = values[AppPreferenceKeys.AI_PERSONALITY].orEmpty()
        )
    }

    private fun validate(state: AppPreferencesState) {
        validateAppearance(
            AppearanceSettings(state.theme, state.appFontSizeSp, state.widgetFontSizeSp)
        )
        require(state.activeConversationId == null || state.activeConversationId.isNotBlank()) {
            "Active conversation ID cannot be blank"
        }
        validateAi(state.ai)
        if (state.webDav.enabled) {
            require(isValidWebDavSettings(state.webDav.url, state.webDav.username)) {
                "WebDAV settings are invalid"
            }
        } else if (state.webDav.url.isNotBlank() || state.webDav.username.isNotBlank()) {
            require(isValidWebDavSettings(state.webDav.url, state.webDav.username)) {
                "WebDAV settings are invalid"
            }
        }
        state.navigation.selectedDate?.let { date ->
            require(date.year in 1..MAX_FOUR_DIGIT_YEAR) {
                "Selected date must use a four-digit year"
            }
        }
    }

    private companion object {
        val FONT_RANGE = 8..20
    }
}

private fun key(name: String) = PreferenceWireCodec.string(name)

private operator fun Preferences.get(name: String): String? = this[key(name)]

private fun setOrRemove(
    values: androidx.datastore.preferences.core.MutablePreferences,
    name: String,
    value: String?
) {
    if (value == null) values.remove(key(name)) else values[key(name)] = value
}

private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == raw } ?: default

private fun strictBoolean(raw: String?): Boolean? = when (raw) {
    "true" -> true
    "false" -> false
    else -> null
}

private fun intInRangeOrDefault(raw: String?, range: IntRange, default: Int): Int =
    raw?.toIntOrNull()?.takeIf { it in range } ?: default

private fun positiveIntOrDefault(raw: String?, default: Int): Int =
    raw?.toIntOrNull()?.takeIf { it > 0 } ?: default

private fun strictLocalDateOrNull(raw: String?): LocalDate? {
    if (raw == null || !Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(raw)) return null
    return try {
        LocalDate.parse(raw).takeIf { it.year in 1..MAX_FOUR_DIGIT_YEAR }
    } catch (_: DateTimeException) {
        null
    }
}

private fun isValidWebDavSettings(url: String, username: String): Boolean =
    runCatching { WebDavSettingsPolicy.validate(url, username) }.isSuccess

private const val MAX_FOUR_DIGIT_YEAR = 9_999
