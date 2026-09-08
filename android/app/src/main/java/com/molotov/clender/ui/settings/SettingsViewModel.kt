package com.molotov.clender.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molotov.clender.ui.foundation.AppearanceUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class SettingsViewModel(private val port: SettingsPort) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    private val mutableCommittedAppearance = MutableStateFlow(
        checkNotNull(AppearanceSettingsDraft().toAppearance())
    )
    val committedAppearance: StateFlow<AppearanceUiState> =
        mutableCommittedAppearance.asStateFlow()

    private var persisted = PersistedSettings()
    private var activationJob: Job? = null
    private var operationJob: Job? = null
    private var activated = false
    private var operationInProgress = false
    private var pendingNavigation: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            port.webDavSyncState.collect { status ->
                mutableState.value = mutableState.value.copy(webDavSyncStatus = status)
            }
        }
    }

    val activate: () -> Unit = activate@{
        if (activated) return@activate
        activated = true
        mutableState.value = mutableState.value.copy(status = SettingsStatus.LOADING)
        activationJob = viewModelScope.launch {
            try {
                val loaded = port.load()
                persisted = loaded
                mutableCommittedAppearance.value = checkNotNull(loaded.appearance.toAppearance())
                mutableState.value = SettingsUiState(
                    active = true,
                    section = mutableState.value.section,
                    appearance = loaded.appearance,
                    ai = loaded.ai,
                    apiKeyConfigured = loaded.apiKeyConfigured,
                    webDav = loaded.webDav,
                    webDavPasswordConfigured = loaded.webDavPasswordConfigured,
                    webDavSyncStatus = mutableState.value.webDavSyncStatus,
                    status = SettingsStatus.READY
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: RuntimeException) {
                mutableState.value = mutableState.value.copy(
                    active = true,
                    status = SettingsStatus.INTERNAL
                )
            }
        }
    }

    val showAiSection: () -> Unit = {
        mutableState.value = mutableState.value.copy(section = SettingsSection.AI)
    }

    val showApplicationSection: () -> Unit = {
        mutableState.value = mutableState.value.copy(section = SettingsSection.APPLICATION)
    }

    val selectSection: (SettingsSection) -> Unit = { section ->
        mutableState.value = mutableState.value.copy(section = section)
    }

    val updateAppearance: (AppearanceSettingsDraft) -> Unit = { value ->
        mutableState.value = mutableState.value.copy(appearance = value)
        refreshDirty()
    }

    val updateAi: (AiSettingsDraft) -> Unit = { value ->
        val current = mutableState.value
        val providerChanged = value.endpoint != current.ai.endpoint
        val next = if (!providerChanged && value.model != current.ai.model) {
            value.applyCapabilities(current.modelCapabilities[value.model])
        } else {
            value
        }
        mutableState.value = current.copy(
            ai = next,
            models = if (providerChanged) emptyList() else current.models,
            modelCapabilities = if (providerChanged) emptyMap() else current.modelCapabilities
        )
        refreshDirty()
    }

    val updateSecretInput: (CharArray) -> Unit = { value ->
        try {
            mutableState.value.secretInput.replace(value)
            mutableState.value = mutableState.value.copy(removeKeyPending = false)
            refreshDirty()
        } finally {
            value.fill(NULL_CHAR)
        }
    }

    val requestApiKeyRemoval: () -> Unit = requestRemoval@{
        if (!mutableState.value.apiKeyConfigured) return@requestRemoval
        mutableState.value = mutableState.value.copy(removeKeyConfirmation = true)
    }

    val confirmApiKeyRemoval: () -> Unit = {
        mutableState.value.secretInput.clear()
        mutableState.value = mutableState.value.copy(
            removeKeyConfirmation = false,
            removeKeyPending = true
        )
        refreshDirty()
    }

    val cancelApiKeyRemoval: () -> Unit = {
        mutableState.value = mutableState.value.copy(removeKeyConfirmation = false)
        refreshDirty()
    }

    val updateWebDav: (WebDavSettingsDraft) -> Unit = { value ->
        mutableState.value = mutableState.value.copy(webDav = value)
        refreshDirty()
    }

    val updateWebDavSecretInput: (CharArray) -> Unit = { value ->
        try {
            mutableState.value.webDavSecretInput.replace(value)
            mutableState.value = mutableState.value.copy(
                webDavPasswordRemoveConfirmation = false,
                webDavPasswordRemovePending = false
            )
            refreshDirty()
        } finally {
            value.fill(NULL_CHAR)
        }
    }

    val requestWebDavPasswordRemoval: () -> Unit = requestWebDavRemoval@{
        if (!mutableState.value.webDavPasswordConfigured) return@requestWebDavRemoval
        mutableState.value = mutableState.value.copy(webDavPasswordRemoveConfirmation = true)
    }

    val confirmWebDavPasswordRemoval: () -> Unit = {
        mutableState.value.webDavSecretInput.clear()
        mutableState.value = mutableState.value.copy(
            webDavPasswordRemoveConfirmation = false,
            webDavPasswordRemovePending = true
        )
        refreshDirty()
    }

    val cancelWebDavPasswordRemoval: () -> Unit = {
        mutableState.value = mutableState.value.copy(webDavPasswordRemoveConfirmation = false)
        refreshDirty()
    }

    val saveAppearance: () -> Unit = saveAppearance@{
        if (operationInProgress) return@saveAppearance
        val draft = mutableState.value.appearance
        val errors = draft.validate()
        if (errors.isNotEmpty()) {
            showValidation(errors)
            return@saveAppearance
        }
        runOperation(SettingsStatus.SAVING) {
            when (port.saveAppearance(draft)) {
                SettingsSaveDecision.SUCCESS -> {
                    persisted = persisted.copy(appearance = draft)
                    mutableCommittedAppearance.value = checkNotNull(draft.toAppearance())
                    mutableState.value = mutableState.value.copy(status = SettingsStatus.READY)
                    refreshDirty()
                }

                SettingsSaveDecision.VALIDATION_FAILED -> showValidation(draft.validate())

                SettingsSaveDecision.SAVE_FAILED -> setStatus(SettingsStatus.SAVE_FAILED)

                SettingsSaveDecision.INTERNAL -> setStatus(SettingsStatus.INTERNAL)
            }
        }
    }

    val saveAi: () -> Unit = saveAi@{
        if (operationInProgress) return@saveAi
        val draft = mutableState.value.ai
        val errors = draft.validate()
        if (errors.isNotEmpty()) {
            showValidation(errors)
            return@saveAi
        }
        val secret = secretForOperation()
        val mutation = currentMutation(secret)
        runOperation(SettingsStatus.SAVING) {
            try {
                when (port.saveAi(draft, mutation, secret)) {
                    SettingsSaveDecision.SUCCESS -> applyAiSaved(draft, mutation)
                    SettingsSaveDecision.VALIDATION_FAILED -> showValidation(draft.validate())
                    SettingsSaveDecision.SAVE_FAILED -> setStatus(SettingsStatus.SAVE_FAILED)
                    SettingsSaveDecision.INTERNAL -> setStatus(SettingsStatus.INTERNAL)
                }
            } finally {
                secret?.fill(NULL_CHAR)
            }
        }
    }

    val fetchModels: () -> Unit = fetchModels@{
        if (operationInProgress) return@fetchModels
        val draft = mutableState.value.ai
        val errors = draft.validate()
        if (errors.isNotEmpty()) {
            showValidation(errors)
            return@fetchModels
        }
        val secret = secretForOperation()
        val mutation = currentMutation(secret)
        runOperation(SettingsStatus.FETCHING) {
            try {
                val result = port.fetchModels(draft, mutation, secret)
                currentCoroutineContext().ensureActive()
                applyFetchResult(draft, mutation, result)
            } finally {
                secret?.fill(NULL_CHAR)
            }
        }
    }

    val saveWebDav: () -> Unit = saveWebDav@{
        if (operationInProgress) return@saveWebDav
        val draft = mutableState.value.webDav
        val errors = mutableState.value.webDavValidationErrors()
        if (errors.isNotEmpty()) {
            setStatus(SettingsStatus.VALIDATION_FAILED)
            return@saveWebDav
        }
        val secret = mutableState.value.webDavSecretCopyOrNull()
        val mutation = mutableState.value.currentWebDavMutation(secret)
        runOperation(SettingsStatus.SAVING) {
            try {
                when (port.saveWebDav(draft, mutation, secret)) {
                    WebDavSaveDecision.SUCCESS -> applyWebDavSaved(draft, mutation)

                    WebDavSaveDecision.VALIDATION_FAILED ->
                        setStatus(SettingsStatus.VALIDATION_FAILED)

                    WebDavSaveDecision.SAVE_FAILED -> setStatus(SettingsStatus.SAVE_FAILED)

                    WebDavSaveDecision.INTERNAL -> setStatus(SettingsStatus.INTERNAL)
                }
            } finally {
                secret?.fill(NULL_CHAR)
            }
        }
    }

    val testWebDavConnection: () -> Unit = testWebDavConnection@{
        if (operationInProgress) return@testWebDavConnection
        val draft = mutableState.value.webDav
        val errors = mutableState.value.webDavValidationErrors()
        if (errors.isNotEmpty()) {
            mutableState.value = mutableState.value.copy(
                connectionResult = WebDavConnectionDecision.VALIDATION_FAILED,
                status = SettingsStatus.VALIDATION_FAILED
            )
            return@testWebDavConnection
        }
        val secret = mutableState.value.webDavSecretCopyOrNull()
        runOperation(SettingsStatus.TESTING) {
            try {
                val result = port.testWebDavConnection(
                    draft,
                    secret,
                    mutableState.value.webDavPasswordRemovePending
                )
                mutableState.value = mutableState.value.copy(
                    connectionResult = result,
                    status = SettingsStatus.READY
                )
            } finally {
                secret?.fill(NULL_CHAR)
            }
        }
    }

    val syncWebDavNow: () -> Unit = syncWebDavNow@{
        if (operationInProgress) return@syncWebDavNow
        val draft = mutableState.value.webDav
        val errors = mutableState.value.webDavValidationErrors()
        if (errors.isNotEmpty()) {
            mutableState.value = mutableState.value.copy(
                syncNowResult = WebDavSyncNowDecision.VALIDATION_FAILED,
                status = SettingsStatus.VALIDATION_FAILED
            )
            return@syncWebDavNow
        }
        val secret = mutableState.value.webDavSecretCopyOrNull()
        val mutation = mutableState.value.currentWebDavMutation(secret)
        runOperation(SettingsStatus.SAVING) {
            try {
                val result = port.syncWebDavNow(
                    draft,
                    secret,
                    mutableState.value.webDavPasswordRemovePending
                )
                when (result) {
                    WebDavSyncNowDecision.SYNC_STARTED,
                    WebDavSyncNowDecision.SYNC_COALESCED,
                    WebDavSyncNowDecision.DISABLED,
                    WebDavSyncNowDecision.UNCONFIGURED -> {
                        applyWebDavSaved(draft, mutation)
                        mutableState.value = mutableState.value.copy(syncNowResult = result)
                    }

                    WebDavSyncNowDecision.VALIDATION_FAILED -> {
                        mutableState.value = mutableState.value.copy(
                            syncNowResult = result,
                            status = SettingsStatus.VALIDATION_FAILED
                        )
                    }

                    WebDavSyncNowDecision.SAVE_FAILED -> {
                        mutableState.value = mutableState.value.copy(
                            syncNowResult = result,
                            status = SettingsStatus.SAVE_FAILED
                        )
                    }

                    WebDavSyncNowDecision.INTERNAL -> {
                        mutableState.value = mutableState.value.copy(
                            syncNowResult = result,
                            status = SettingsStatus.INTERNAL
                        )
                    }
                }
            } finally {
                secret?.fill(NULL_CHAR)
            }
        }
    }

    val leaveSettings: () -> Unit = {
        cancelFetchAndWipeSecret()
        cancelWebDavProbe()
        mutableState.value = mutableState.value.copy(
            removeKeyConfirmation = false,
            removeKeyPending = false,
            discardConfirmation = false,
            dialog = SettingsDialog.NONE,
            webDavPasswordRemoveConfirmation = false,
            webDavPasswordRemovePending = false,
            connectionResult = null,
            syncNowResult = null,
            status = SettingsStatus.READY
        )
        restorePersistedDrafts()
    }

    val setDrawerOpen: (Boolean) -> Unit = { open ->
        mutableState.value = mutableState.value.copy(drawerOpen = open)
    }

    val showDialog: (SettingsDialog) -> Unit = { dialog ->
        mutableState.value = mutableState.value.copy(dialog = dialog)
    }

    val handleBack: () -> SettingsBackResult = {
        val current = mutableState.value
        when {
            current.removeKeyConfirmation -> {
                cancelApiKeyRemoval()
                SettingsBackResult.KEY_REMOVE_CLOSED
            }

            current.webDavPasswordRemoveConfirmation -> {
                cancelWebDavPasswordRemoval()
                SettingsBackResult.KEY_REMOVE_CLOSED
            }

            current.dialog != SettingsDialog.NONE -> {
                mutableState.value = current.copy(dialog = SettingsDialog.NONE)
                SettingsBackResult.DIALOG_CLOSED
            }

            current.drawerOpen -> {
                mutableState.value = current.copy(drawerOpen = false)
                SettingsBackResult.DRAWER_CLOSED
            }

            current.dirty -> {
                mutableState.value = current.copy(discardConfirmation = true)
                SettingsBackResult.DISCARD_REQUIRED
            }

            else -> SettingsBackResult.NAVIGATE
        }
    }

    val requestNavigation: (() -> Unit) -> Boolean = requestNavigation@{ action ->
        if (!mutableState.value.dirty) {
            action()
            return@requestNavigation true
        }
        pendingNavigation = action
        mutableState.value = mutableState.value.copy(discardConfirmation = true)
        false
    }

    val confirmDiscard: () -> Unit = {
        val action = pendingNavigation
        pendingNavigation = null
        mutableState.value.secretInput.clear()
        restorePersistedDrafts()
        action?.invoke()
    }

    val cancelDiscard: () -> Unit = {
        pendingNavigation = null
        mutableState.value = mutableState.value.copy(discardConfirmation = false)
    }

    override fun onCleared() {
        cancelFetchAndWipeSecret()
        cancelWebDavProbe()
        activationJob?.cancel()
        super.onCleared()
    }

    private fun runOperation(status: SettingsStatus, block: suspend () -> Unit) {
        operationInProgress = true
        mutableState.value = mutableState.value.copy(status = status, validationErrors = emptySet())
        operationJob = viewModelScope.launch {
            try {
                yield()
                block()
            } catch (failure: CancellationException) {
                if (operationJob ===
                    currentCoroutineContext()[Job]
                ) {
                    setStatus(SettingsStatus.CANCELLED)
                }
                throw failure
            } catch (_: RuntimeException) {
                if (operationJob ===
                    currentCoroutineContext()[Job]
                ) {
                    setStatus(SettingsStatus.INTERNAL)
                }
            } finally {
                if (operationJob === currentCoroutineContext()[Job]) operationInProgress = false
            }
        }
    }

    private fun applyAiSaved(draft: AiSettingsDraft, mutation: ApiKeyMutation) {
        val configured = when (mutation) {
            ApiKeyMutation.KEEP -> persisted.apiKeyConfigured
            ApiKeyMutation.REPLACE -> true
            ApiKeyMutation.REMOVE -> false
        }
        persisted = persisted.copy(ai = draft, apiKeyConfigured = configured)
        mutableState.value.secretInput.clear()
        mutableState.value = mutableState.value.copy(
            ai = draft,
            apiKeyConfigured = configured,
            removeKeyPending = false,
            status = SettingsStatus.READY
        )
        refreshDirty()
    }

    private fun applyFetchResult(
        draft: AiSettingsDraft,
        mutation: ApiKeyMutation,
        result: ModelFetchResult
    ) {
        if (result.decision in DECISIONS_AFTER_SUCCESSFUL_SAVE) {
            applyAiSaved(draft, mutation)
        }
        val models = if (result.decision == ModelFetchDecision.SUCCESS) {
            result.models.filter(String::isNotBlank).distinct()
        } else {
            mutableState.value.models
        }
        val status = if (result.decision == ModelFetchDecision.SUCCESS && models.isEmpty()) {
            SettingsStatus.MODELS_EMPTY
        } else {
            result.decision.toStatus()
        }
        val capabilities = if (result.decision == ModelFetchDecision.SUCCESS) {
            result.capabilities.filterKeys { it in models }
        } else {
            mutableState.value.modelCapabilities
        }
        val current = mutableState.value
        val draftWithLimits = if (result.decision == ModelFetchDecision.SUCCESS) {
            current.ai.applyCapabilities(capabilities[current.ai.model])
        } else {
            current.ai
        }
        mutableState.value = current.copy(
            models = models,
            status = status,
            ai = draftWithLimits,
            modelCapabilities = capabilities
        )
        refreshDirty()
    }

    private fun applyWebDavSaved(draft: WebDavSettingsDraft, mutation: ApiKeyMutation) {
        val configured = when (mutation) {
            ApiKeyMutation.KEEP -> persisted.webDavPasswordConfigured
            ApiKeyMutation.REPLACE -> true
            ApiKeyMutation.REMOVE -> false
        }
        persisted = persisted.copy(webDav = draft, webDavPasswordConfigured = configured)
        mutableState.value.webDavSecretInput.clear()
        mutableState.value = mutableState.value.copy(
            webDav = draft,
            webDavPasswordConfigured = configured,
            webDavPasswordRemovePending = false,
            status = SettingsStatus.READY
        )
        refreshDirty()
    }

    private fun secretForOperation(): CharArray? = mutableState.value.secretInput
        .takeUnless { it.isEmpty() || it.isBlank() }
        ?.copyChars()

    private fun currentMutation(secret: CharArray?): ApiKeyMutation = when {
        mutableState.value.removeKeyPending -> ApiKeyMutation.REMOVE
        secret != null -> ApiKeyMutation.REPLACE
        else -> ApiKeyMutation.KEEP
    }

    private fun cancelWebDavProbe() {
        port.cancelWebDavProbe()
        operationJob?.cancel()
        operationJob = null
        operationInProgress = false
    }

    private fun restorePersistedDrafts() {
        mutableState.value.secretInput.clear()
        mutableState.value.webDavSecretInput.clear()
        mutableState.value = mutableState.value.copy(
            appearance = persisted.appearance,
            ai = persisted.ai,
            apiKeyConfigured = persisted.apiKeyConfigured,
            removeKeyPending = false,
            webDav = persisted.webDav,
            webDavPasswordConfigured = persisted.webDavPasswordConfigured,
            webDavPasswordRemoveConfirmation = false,
            webDavPasswordRemovePending = false,
            discardConfirmation = false,
            dirty = false
        )
    }

    private val refreshDirty: () -> Unit = {
        val current = mutableState.value
        mutableState.value = current.copy(
            dirty = current.appearance != persisted.appearance ||
                current.ai != persisted.ai ||
                !current.secretInput.isEmpty() ||
                current.removeKeyPending ||
                current.webDav != persisted.webDav ||
                !current.webDavSecretInput.isEmpty() ||
                current.webDavPasswordRemovePending
        )
    }

    private val showValidation: (Set<SettingsValidationError>) -> Unit = { errors ->
        mutableState.value = mutableState.value.copy(
            status = SettingsStatus.VALIDATION_FAILED,
            validationErrors = errors
        )
    }

    private val setStatus: (SettingsStatus) -> Unit = { status ->
        mutableState.value = mutableState.value.copy(status = status)
    }

    private fun cancelFetchAndWipeSecret() {
        port.cancelModelFetch()
        operationJob?.cancel()
        operationJob = null
        operationInProgress = false
        mutableState.value.secretInput.clear()
    }

    private companion object {
        const val NULL_CHAR = '\u0000'
        val DECISIONS_AFTER_SUCCESSFUL_SAVE = setOf(
            ModelFetchDecision.SUCCESS,
            ModelFetchDecision.UNCONFIGURED,
            ModelFetchDecision.TIMEOUT,
            ModelFetchDecision.NETWORK,
            ModelFetchDecision.PROVIDER,
            ModelFetchDecision.INVALID_RESPONSE,
            ModelFetchDecision.CANCELLED,
            ModelFetchDecision.INTERNAL
        )
    }
}
