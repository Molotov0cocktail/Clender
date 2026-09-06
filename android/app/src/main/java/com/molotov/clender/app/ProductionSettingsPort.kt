package com.molotov.clender.app

import com.molotov.clender.app.settings.AiSettingsApplicationService
import com.molotov.clender.app.settings.AppearanceSettingsApplicationService
import com.molotov.clender.app.settings.ModelFetchDecision
import com.molotov.clender.app.settings.SettingsSaveDecision
import com.molotov.clender.app.settings.WebDavSettingsApplicationService
import com.molotov.clender.app.sync.SyncState
import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.data.settings.ApiKeyMutation
import com.molotov.clender.data.settings.AppPreferencesState
import com.molotov.clender.data.settings.AppearanceSettings
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.settings.AiSettingsDraft
import com.molotov.clender.ui.settings.AppearanceSettingsDraft
import com.molotov.clender.ui.settings.PersistedSettings
import com.molotov.clender.ui.settings.SettingsPort
import com.molotov.clender.ui.settings.WebDavSettingsDraft
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

internal data class WebDavPortDependencies(
    val service: () -> WebDavSettingsApplicationService,
    val syncState: () -> StateFlow<SyncState>,
    val passwordConfigured: suspend () -> Boolean
)

internal class ProductionSettingsPort(
    private val loadState: suspend () -> AppPreferencesState,
    private val isApiKeyConfigured: suspend () -> Boolean,
    private val webDav: WebDavPortDependencies,
    private val appearanceService: () -> AppearanceSettingsApplicationService,
    private val aiService: () -> AiSettingsApplicationService,
    private val cancelAiClient: () -> Unit
) : SettingsPort {
    private val modelFetchActive = AtomicBoolean(false)

    override suspend fun load(): PersistedSettings {
        val state = loadState()
        return PersistedSettings(
            appearance = state.toAppearanceDraft(),
            ai = state.ai.toDraft(),
            apiKeyConfigured = isApiKeyConfigured(),
            webDav = state.webDav.toDraft(),
            webDavPasswordConfigured = webDav.passwordConfigured()
        )
    }

    override suspend fun saveAppearance(
        draft: AppearanceSettingsDraft
    ): com.molotov.clender.ui.settings.SettingsSaveDecision {
        val appearance = draft.toDataSettings()
            ?: return com.molotov.clender.ui.settings.SettingsSaveDecision.VALIDATION_FAILED
        return appearanceService().save(appearance).toUiDecision()
    }

    override suspend fun saveAi(
        draft: AiSettingsDraft,
        mutation: com.molotov.clender.ui.settings.ApiKeyMutation,
        key: CharArray?
    ): com.molotov.clender.ui.settings.SettingsSaveDecision {
        val dataMutation = mutation.toDataMutation(key)
            ?: return com.molotov.clender.ui.settings.SettingsSaveDecision.VALIDATION_FAILED
        return try {
            aiService().saveAi(draft.toDataSettings(), dataMutation).toUiDecision()
        } finally {
            key?.fill(NULL_CHAR)
        }
    }

    override suspend fun fetchModels(
        draft: AiSettingsDraft,
        mutation: com.molotov.clender.ui.settings.ApiKeyMutation,
        key: CharArray?
    ): com.molotov.clender.ui.settings.ModelFetchResult {
        val dataMutation = mutation.toDataMutation(key)
            ?: return com.molotov.clender.ui.settings.ModelFetchResult(
                com.molotov.clender.ui.settings.ModelFetchDecision.VALIDATION_FAILED
            )
        modelFetchActive.set(true)
        return try {
            val result = aiService().fetchModels(draft.toDataSettings(), dataMutation)
            com.molotov.clender.ui.settings.ModelFetchResult(
                decision = result.decision.toUiDecision(),
                models = result.models
            )
        } finally {
            modelFetchActive.set(false)
            key?.fill(NULL_CHAR)
        }
    }

    override fun cancelModelFetch() {
        if (modelFetchActive.get()) cancelAiClient()
    }

    override suspend fun saveWebDav(
        draft: WebDavSettingsDraft,
        mutation: com.molotov.clender.ui.settings.ApiKeyMutation,
        password: CharArray?
    ): com.molotov.clender.ui.settings.WebDavSaveDecision {
        val dataMutation = mutation.toWebDavMutation(password)
            ?: return com.molotov.clender.ui.settings.WebDavSaveDecision.VALIDATION_FAILED
        return try {
            webDav.service().save(draft.toWebDavPreferences(), dataMutation).toUi()
        } finally {
            password?.fill(NULL_CHAR)
        }
    }

    override suspend fun testWebDavConnection(
        draft: WebDavSettingsDraft,
        password: CharArray?,
        removePasswordPending: Boolean
    ): com.molotov.clender.ui.settings.WebDavConnectionDecision = try {
        webDav.service()
            .testConnection(draft.toWebDavPreferences(), password, removePasswordPending)
            .toUi()
    } finally {
        password?.fill(NULL_CHAR)
    }

    override suspend fun syncWebDavNow(
        draft: WebDavSettingsDraft,
        password: CharArray?,
        removePasswordPending: Boolean
    ): com.molotov.clender.ui.settings.WebDavSyncNowDecision = try {
        webDav.service()
            .syncNow(draft.toWebDavPreferences(), password, removePasswordPending)
            .toUi()
    } finally {
        password?.fill(NULL_CHAR)
    }

    override fun cancelWebDavProbe() {
        webDav.service().cancelProbe()
    }

    override val webDavSyncState: Flow<com.molotov.clender.ui.settings.WebDavSyncStatusUi>
        get() = webDav.syncState().map(SyncState::toUiStatus)
}

private fun AppPreferencesState.toAppearanceDraft(): AppearanceSettingsDraft =
    AppearanceSettingsDraft(
        themeMode = when (theme) {
            com.molotov.clender.data.settings.ThemeMode.SYSTEM -> ThemeMode.SYSTEM
            com.molotov.clender.data.settings.ThemeMode.LIGHT -> ThemeMode.LIGHT
            com.molotov.clender.data.settings.ThemeMode.DARK -> ThemeMode.DARK
        },
        appFontSize = appFontSizeSp.toString(),
        widgetFontSize = widgetFontSizeSp.toString()
    )

private fun AppearanceSettingsDraft.toDataSettings(): AppearanceSettings? {
    val appSize = appFontSize.toIntOrNull()
    val widgetSize = widgetFontSize.toIntOrNull()
    return if (appSize == null || widgetSize == null) {
        null
    } else {
        AppearanceSettings(
            theme = when (themeMode) {
                ThemeMode.SYSTEM -> com.molotov.clender.data.settings.ThemeMode.SYSTEM
                ThemeMode.LIGHT -> com.molotov.clender.data.settings.ThemeMode.LIGHT
                ThemeMode.DARK -> com.molotov.clender.data.settings.ThemeMode.DARK
            },
            appFontSizeSp = appSize,
            widgetFontSizeSp = widgetSize
        )
    }
}

private fun AiSettings.toDraft(): AiSettingsDraft = AiSettingsDraft(
    endpoint = endpoint,
    model = model,
    temperature = temperature.toString(),
    maxOutputTokens = maxOutputTokens.toString(),
    contextWindow = contextWindow.toString(),
    thinkingEnabled = thinkingEnabled,
    thinkingEffort = thinkingEffort,
    systemPrompt = systemPrompt,
    personality = personality
)

private fun AiSettingsDraft.toDataSettings(): AiSettings = AiSettings(
    endpoint = endpoint,
    model = model,
    temperature = temperature.toDoubleOrNull() ?: Double.NaN,
    maxOutputTokens = maxOutputTokens.toIntOrNull() ?: 0,
    contextWindow = contextWindow.toIntOrNull() ?: 0,
    thinkingEnabled = thinkingEnabled,
    thinkingEffort = thinkingEffort,
    systemPrompt = systemPrompt,
    personality = personality
)

private fun com.molotov.clender.ui.settings.ApiKeyMutation.toDataMutation(
    key: CharArray?
): ApiKeyMutation? = when (this) {
    com.molotov.clender.ui.settings.ApiKeyMutation.KEEP -> ApiKeyMutation.Keep

    com.molotov.clender.ui.settings.ApiKeyMutation.REPLACE ->
        key?.let(ApiKeyMutation::Replace)

    com.molotov.clender.ui.settings.ApiKeyMutation.REMOVE -> ApiKeyMutation.Remove
}

private fun SettingsSaveDecision.toUiDecision():
    com.molotov.clender.ui.settings.SettingsSaveDecision =
    when (this) {
        SettingsSaveDecision.SUCCESS -> com.molotov.clender.ui.settings.SettingsSaveDecision.SUCCESS

        SettingsSaveDecision.VALIDATION_FAILED ->
            com.molotov.clender.ui.settings.SettingsSaveDecision.VALIDATION_FAILED

        SettingsSaveDecision.SAVE_FAILED ->
            com.molotov.clender.ui.settings.SettingsSaveDecision.SAVE_FAILED

        SettingsSaveDecision.INTERNAL ->
            com.molotov.clender.ui.settings.SettingsSaveDecision.INTERNAL
    }

private fun ModelFetchDecision.toUiDecision(): com.molotov.clender.ui.settings.ModelFetchDecision =
    com.molotov.clender.ui.settings.ModelFetchDecision.valueOf(name)

private const val NULL_CHAR = '\u0000'
