package com.molotov.clender.ui.settings

import com.molotov.clender.data.network.ai.AiModelCapabilities
import com.molotov.clender.data.settings.AiEndpointPolicy
import com.molotov.clender.data.settings.ThinkingEffort
import com.molotov.clender.data.settings.WebDavSettingsPolicy
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val MIN_FONT_SIZE_SP = 8
private const val MAX_FONT_SIZE_SP = 20
private const val DEFAULT_FONT_SIZE_SP = 13

enum class SettingsSection {
    APPLICATION,
    AI,
    WEBDAV
}

enum class SettingsValidationError {
    APP_FONT_SIZE,
    WIDGET_FONT_SIZE,
    AI_ENDPOINT,
    AI_TEMPERATURE,
    AI_MAX_OUTPUT_TOKENS,
    AI_CONTEXT_WINDOW
}

data class AppearanceSettingsDraft(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val appFontSize: String = DEFAULT_FONT_SIZE,
    val widgetFontSize: String = DEFAULT_FONT_SIZE
) {
    fun validate(): Set<SettingsValidationError> = buildSet {
        if (appFontSize.toIntOrNull() !in MIN_FONT_SIZE_SP..MAX_FONT_SIZE_SP) {
            add(SettingsValidationError.APP_FONT_SIZE)
        }
        if (widgetFontSize.toIntOrNull() !in MIN_FONT_SIZE_SP..MAX_FONT_SIZE_SP) {
            add(SettingsValidationError.WIDGET_FONT_SIZE)
        }
    }

    fun toAppearance(): AppearanceUiState? {
        if (validate().isNotEmpty()) return null
        return AppearanceUiState(
            themeMode = themeMode,
            appFontSizeSp = checkNotNull(appFontSize.toIntOrNull()),
            widgetFontSizeSp = checkNotNull(widgetFontSize.toIntOrNull())
        )
    }

    private companion object {
        const val DEFAULT_FONT_SIZE = "13"
    }
}

data class AiSettingsDraft(
    val endpoint: String = "",
    val model: String = "",
    val temperature: String = DEFAULT_TEMPERATURE,
    val maxOutputTokens: String = DEFAULT_MAX_OUTPUT,
    val contextWindow: String = DEFAULT_CONTEXT,
    val thinkingEnabled: Boolean = true,
    val thinkingEffort: ThinkingEffort = ThinkingEffort.HIGH,
    val systemPrompt: String = "",
    val personality: String = ""
) {
    fun validate(): Set<SettingsValidationError> = buildSet {
        val normalizedEndpoint = endpoint.trim()
        if (normalizedEndpoint.isNotEmpty() && !isValidEndpoint(normalizedEndpoint)) {
            add(SettingsValidationError.AI_ENDPOINT)
        }
        val temperatureValue = temperature.toDoubleOrNull()
        if (temperatureValue == null || !temperatureValue.isFinite() ||
            temperatureValue !in MIN_TEMPERATURE..MAX_TEMPERATURE
        ) {
            add(SettingsValidationError.AI_TEMPERATURE)
        }
        val maxOutputValue = maxOutputTokens.toIntOrNull()
        if (maxOutputValue == null || maxOutputValue <= 0) {
            add(SettingsValidationError.AI_MAX_OUTPUT_TOKENS)
        }
        val contextValue = contextWindow.toIntOrNull()
        val contextIsInvalid = contextValue == null || contextValue <= 0
        val orderingIsInvalid = contextValue != null &&
            maxOutputValue != null && contextValue <= maxOutputValue
        if (contextIsInvalid || maxOutputValue == null || orderingIsInvalid) {
            add(SettingsValidationError.AI_CONTEXT_WINDOW)
        }
    }

    private fun isValidEndpoint(value: String): Boolean = runCatching {
        AiEndpointPolicy.resolve(value, "models")
    }.isSuccess

    private companion object {
        const val DEFAULT_TEMPERATURE = "0.7"
        const val DEFAULT_MAX_OUTPUT = "4096"
        const val DEFAULT_CONTEXT = "128000"
        const val MIN_TEMPERATURE = 0.0
        const val MAX_TEMPERATURE = 2.0
    }
}

enum class WebDavValidationError {
    URL,
    USERNAME,
    PASSWORD_REQUIRED,
    REMOVE_WHILE_ENABLED
}

data class WebDavSettingsDraft(
    val enabled: Boolean = false,
    val url: String = "",
    val username: String = ""
) {
    /**
     * Combination validation for the WebDAV section. Attribution is deterministic:
     * - trimmed URL blank or unparseable -> URL (takes precedence over username);
     * - URL parseable but WebDavSettingsPolicy.validate fails (scheme/credentials/query/
     *   fragment/path rules) or username empty/colon/bad Unicode -> USERNAME;
     * - disabled blank draft is always legal; disabled with values still runs the policy;
     * - enabled requires a usable password unless a removal is pending.
     */
    fun validate(
        passwordConfigured: Boolean,
        passwordInputEmpty: Boolean,
        passwordRemovePending: Boolean
    ): Set<WebDavValidationError> = buildSet {
        val normalizedUrl = url.trim()
        val normalizedUsername = username.trim()
        val disabledAndBlank = !enabled && normalizedUrl.isEmpty() && normalizedUsername.isEmpty()
        if (!disabledAndBlank) {
            val urlUnusable = normalizedUrl.isEmpty() || normalizedUrl.toHttpUrlOrNull() == null
            if (urlUnusable) {
                add(WebDavValidationError.URL)
            } else {
                val policyValid = runCatching {
                    WebDavSettingsPolicy.validate(url, username)
                }.isSuccess
                if (!policyValid) {
                    add(WebDavValidationError.USERNAME)
                }
            }
        }
        if (enabled && passwordRemovePending) {
            add(WebDavValidationError.REMOVE_WHILE_ENABLED)
        }
        val passwordRequired = enabled &&
            !passwordRemovePending &&
            passwordInputEmpty &&
            !passwordConfigured
        if (passwordRequired) {
            add(WebDavValidationError.PASSWORD_REQUIRED)
        }
    }
}

class SecretInput private constructor(private var chars: CharArray) {
    fun replace(value: CharArray) {
        clear()
        chars = value.copyOf()
    }

    fun copyChars(): CharArray = chars.copyOf()

    fun isEmpty(): Boolean = chars.isEmpty()

    fun isBlank(): Boolean = chars.all(Char::isWhitespace)

    fun clear() {
        chars.fill(NULL_CHAR)
        chars = CharArray(0)
    }

    override fun toString(): String = REDACTED

    companion object {
        fun empty(): SecretInput = SecretInput(CharArray(0))

        fun from(value: CharArray): SecretInput = SecretInput(value.copyOf())

        private const val NULL_CHAR = '\u0000'
        private const val REDACTED = "SecretInput([redacted])"
    }
}

enum class ApiKeyMutation {
    KEEP,
    REPLACE,
    REMOVE
}

enum class SettingsSaveDecision {
    SUCCESS,
    VALIDATION_FAILED,
    SAVE_FAILED,
    INTERNAL
}

enum class ModelFetchDecision {
    SUCCESS,
    BUSY,
    UNCONFIGURED,
    VALIDATION_FAILED,
    SAVE_FAILED,
    TIMEOUT,
    NETWORK,
    PROVIDER,
    INVALID_RESPONSE,
    CANCELLED,
    INTERNAL
}

data class ModelFetchResult(
    val decision: ModelFetchDecision,
    val models: List<String> = emptyList(),
    val capabilities: Map<String, AiModelCapabilities> = emptyMap()
)

enum class WebDavSaveDecision {
    SUCCESS,
    VALIDATION_FAILED,
    SAVE_FAILED,
    INTERNAL
}

enum class WebDavConnectionDecision {
    SUCCESS,
    BUSY,
    UNCONFIGURED,
    VALIDATION_FAILED,
    AUTH,
    DOCUMENT,
    TRANSPORT,
    SECRET,
    CANCELLED,
    INTERNAL
}

enum class WebDavSyncNowDecision {
    SYNC_STARTED,
    SYNC_COALESCED,
    DISABLED,
    UNCONFIGURED,
    VALIDATION_FAILED,
    SAVE_FAILED,
    INTERNAL
}

enum class WebDavSyncFailureCodeUi {
    AUTH,
    CONFLICT,
    DOCUMENT,
    TRANSPORT,
    SECRET,
    SETTINGS,
    CANCELLED,
    INTERNAL
}

sealed interface WebDavSyncStatusUi {
    data object Disabled : WebDavSyncStatusUi

    data object Unconfigured : WebDavSyncStatusUi

    data object Idle : WebDavSyncStatusUi

    data class Running(val pending: Boolean) : WebDavSyncStatusUi

    data class Success(val uploaded: Boolean, val localChanged: Boolean, val eventCount: Int) :
        WebDavSyncStatusUi

    data class Failed(val code: WebDavSyncFailureCodeUi) : WebDavSyncStatusUi
}

data class PersistedSettings(
    val appearance: AppearanceSettingsDraft = AppearanceSettingsDraft(),
    val ai: AiSettingsDraft = AiSettingsDraft(),
    val apiKeyConfigured: Boolean = false,
    val webDav: WebDavSettingsDraft = WebDavSettingsDraft(),
    val webDavPasswordConfigured: Boolean = false
)

interface SettingsPort {
    suspend fun load(): PersistedSettings

    suspend fun saveAppearance(draft: AppearanceSettingsDraft): SettingsSaveDecision

    suspend fun saveAi(
        draft: AiSettingsDraft,
        mutation: ApiKeyMutation,
        key: CharArray?
    ): SettingsSaveDecision

    suspend fun fetchModels(
        draft: AiSettingsDraft,
        mutation: ApiKeyMutation,
        key: CharArray?
    ): ModelFetchResult

    fun cancelModelFetch()

    suspend fun saveWebDav(
        draft: WebDavSettingsDraft,
        mutation: ApiKeyMutation,
        password: CharArray?
    ): WebDavSaveDecision = error("Not implemented")

    suspend fun testWebDavConnection(
        draft: WebDavSettingsDraft,
        password: CharArray?,
        removePasswordPending: Boolean
    ): WebDavConnectionDecision = error("Not implemented")

    suspend fun syncWebDavNow(
        draft: WebDavSettingsDraft,
        password: CharArray?,
        removePasswordPending: Boolean
    ): WebDavSyncNowDecision = error("Not implemented")

    fun cancelWebDavProbe() = Unit

    val webDavSyncState: Flow<WebDavSyncStatusUi>
        get() = flowOf(WebDavSyncStatusUi.Idle)
}

enum class SettingsStatus {
    INACTIVE,
    LOADING,
    READY,
    SAVING,
    TESTING,
    FETCHING,
    MODELS_EMPTY,
    BUSY,
    UNCONFIGURED,
    VALIDATION_FAILED,
    SAVE_FAILED,
    TIMEOUT,
    NETWORK,
    PROVIDER,
    INVALID_RESPONSE,
    CANCELLED,
    INTERNAL
}

enum class SettingsDialog {
    NONE,
    OTHER
}

enum class SettingsBackResult {
    KEY_REMOVE_CLOSED,
    DIALOG_CLOSED,
    DRAWER_CLOSED,
    DISCARD_REQUIRED,
    NAVIGATE
}

data class SettingsUiState(
    val active: Boolean = false,
    val section: SettingsSection = SettingsSection.APPLICATION,
    val appearance: AppearanceSettingsDraft = AppearanceSettingsDraft(),
    val ai: AiSettingsDraft = AiSettingsDraft(),
    val apiKeyConfigured: Boolean = false,
    val secretInput: SecretInput = SecretInput.empty(),
    val removeKeyConfirmation: Boolean = false,
    val removeKeyPending: Boolean = false,
    val webDav: WebDavSettingsDraft = WebDavSettingsDraft(),
    val webDavPasswordConfigured: Boolean = false,
    val webDavSecretInput: SecretInput = SecretInput.empty(),
    val webDavPasswordRemoveConfirmation: Boolean = false,
    val webDavPasswordRemovePending: Boolean = false,
    val connectionResult: WebDavConnectionDecision? = null,
    val syncNowResult: WebDavSyncNowDecision? = null,
    val webDavSyncStatus: WebDavSyncStatusUi = WebDavSyncStatusUi.Idle,
    val discardConfirmation: Boolean = false,
    val dialog: SettingsDialog = SettingsDialog.NONE,
    val drawerOpen: Boolean = false,
    val dirty: Boolean = false,
    val status: SettingsStatus = SettingsStatus.INACTIVE,
    val validationErrors: Set<SettingsValidationError> = emptySet(),
    val models: List<String> = emptyList(),
    val modelCapabilities: Map<String, AiModelCapabilities> = emptyMap()
) {
    val widgetFontSizeSp: Int
        get() = appearance.widgetFontSize.toIntOrNull()
            ?.takeIf { it in MIN_FONT_SIZE_SP..MAX_FONT_SIZE_SP }
            ?: DEFAULT_FONT_SIZE_SP

    override fun toString(): String = buildString {
        append("SettingsUiState(active=")
        append(active)
        append(", section=")
        append(section)
        append(", appearance=")
        append(appearance)
        append(", ai=")
        append(ai)
        append(", apiKeyConfigured=")
        append(apiKeyConfigured)
        append(", secretInput=[redacted], removeKeyConfirmation=")
        append(removeKeyConfirmation)
        append(", removeKeyPending=")
        append(removeKeyPending)
        append(", webDav=")
        append(webDav)
        append(", webDavPasswordConfigured=")
        append(webDavPasswordConfigured)
        append(", webDavSecretInput=[redacted], webDavPasswordRemoveConfirmation=")
        append(webDavPasswordRemoveConfirmation)
        append(", webDavPasswordRemovePending=")
        append(webDavPasswordRemovePending)
        append(", connectionResult=")
        append(connectionResult)
        append(", syncNowResult=")
        append(syncNowResult)
        append(", webDavSyncStatus=")
        append(webDavSyncStatus)
        append(", discardConfirmation=")
        append(discardConfirmation)
        append(", dialog=")
        append(dialog)
        append(", drawerOpen=")
        append(drawerOpen)
        append(", dirty=")
        append(dirty)
        append(", status=")
        append(status)
        append(", validationErrors=")
        append(validationErrors)
        append(", models=")
        append(models)
        append(')')
    }
}
