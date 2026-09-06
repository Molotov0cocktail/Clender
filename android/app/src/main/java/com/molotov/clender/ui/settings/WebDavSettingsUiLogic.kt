package com.molotov.clender.ui.settings

import androidx.annotation.StringRes
import com.molotov.clender.R

/**
 * T46-C2b WebDAV settings presentation logic shared by the SettingsViewModel:
 * validation combination, bounded secret copies and mutation derivation.
 * Everything is pure over the UI state; no passwords ever leave as state.
 */
internal fun SettingsUiState.webDavValidationErrors(): Set<WebDavValidationError> = webDav.validate(
    passwordConfigured = webDavPasswordConfigured,
    passwordInputEmpty = webDavSecretInput.isEmpty(),
    passwordRemovePending = webDavPasswordRemovePending
)

internal fun SettingsUiState.webDavSecretCopyOrNull(): CharArray? =
    webDavSecretInput.takeUnless { it.isEmpty() || it.isBlank() }?.copyChars()

internal fun SettingsUiState.currentWebDavMutation(secret: CharArray?): ApiKeyMutation = when {
    webDavPasswordRemovePending -> ApiKeyMutation.REMOVE
    secret != null -> ApiKeyMutation.REPLACE
    else -> ApiKeyMutation.KEEP
}

@StringRes
internal fun WebDavConnectionDecision.statusResource(): Int = when (this) {
    WebDavConnectionDecision.SUCCESS -> R.string.settings_webdav_connection_success

    WebDavConnectionDecision.BUSY -> R.string.settings_webdav_connection_busy

    WebDavConnectionDecision.UNCONFIGURED -> R.string.settings_webdav_connection_unconfigured

    WebDavConnectionDecision.VALIDATION_FAILED ->
        R.string.settings_webdav_connection_validation_failed

    WebDavConnectionDecision.AUTH -> R.string.settings_webdav_connection_auth

    WebDavConnectionDecision.DOCUMENT -> R.string.settings_webdav_connection_document

    WebDavConnectionDecision.TRANSPORT -> R.string.settings_webdav_connection_transport

    WebDavConnectionDecision.SECRET -> R.string.settings_webdav_connection_secret

    WebDavConnectionDecision.CANCELLED -> R.string.settings_webdav_connection_cancelled

    WebDavConnectionDecision.INTERNAL -> R.string.settings_webdav_connection_internal
}

@StringRes
internal fun WebDavSyncNowDecision.syncStatusResource(): Int = when (this) {
    WebDavSyncNowDecision.SYNC_STARTED -> R.string.settings_webdav_sync_started
    WebDavSyncNowDecision.SYNC_COALESCED -> R.string.settings_webdav_sync_queued
    WebDavSyncNowDecision.DISABLED -> R.string.settings_webdav_sync_disabled
    WebDavSyncNowDecision.UNCONFIGURED -> R.string.settings_webdav_sync_unconfigured
    WebDavSyncNowDecision.VALIDATION_FAILED -> R.string.settings_webdav_sync_validation_failed
    WebDavSyncNowDecision.SAVE_FAILED -> R.string.settings_webdav_sync_save_failed
    WebDavSyncNowDecision.INTERNAL -> R.string.settings_webdav_sync_failed_internal
}

@StringRes
internal fun WebDavSyncStatusUi.runtimeStatusResource(): Int = when (this) {
    WebDavSyncStatusUi.Disabled -> R.string.settings_webdav_sync_disabled

    WebDavSyncStatusUi.Unconfigured -> R.string.settings_webdav_sync_unconfigured

    WebDavSyncStatusUi.Idle -> R.string.settings_webdav_sync_idle

    is WebDavSyncStatusUi.Failed -> code.failedResource()

    is WebDavSyncStatusUi.Running,
    is WebDavSyncStatusUi.Success -> error("formatted statuses use text mapper")
}

@StringRes
internal fun WebDavSyncFailureCodeUi.failedResource(): Int = when (this) {
    WebDavSyncFailureCodeUi.AUTH -> R.string.settings_webdav_sync_failed_auth
    WebDavSyncFailureCodeUi.CONFLICT -> R.string.settings_webdav_sync_failed_conflict
    WebDavSyncFailureCodeUi.DOCUMENT -> R.string.settings_webdav_sync_failed_document
    WebDavSyncFailureCodeUi.TRANSPORT -> R.string.settings_webdav_sync_failed_transport
    WebDavSyncFailureCodeUi.SECRET -> R.string.settings_webdav_sync_failed_secret
    WebDavSyncFailureCodeUi.SETTINGS -> R.string.settings_webdav_sync_failed_settings
    WebDavSyncFailureCodeUi.CANCELLED -> R.string.settings_webdav_sync_failed_cancelled
    WebDavSyncFailureCodeUi.INTERNAL -> R.string.settings_webdav_sync_failed_internal
}

internal fun ModelFetchDecision.toStatus(): SettingsStatus = when (this) {
    ModelFetchDecision.SUCCESS -> SettingsStatus.READY
    ModelFetchDecision.BUSY -> SettingsStatus.BUSY
    ModelFetchDecision.UNCONFIGURED -> SettingsStatus.UNCONFIGURED
    ModelFetchDecision.VALIDATION_FAILED -> SettingsStatus.VALIDATION_FAILED
    ModelFetchDecision.SAVE_FAILED -> SettingsStatus.SAVE_FAILED
    ModelFetchDecision.TIMEOUT -> SettingsStatus.TIMEOUT
    ModelFetchDecision.NETWORK -> SettingsStatus.NETWORK
    ModelFetchDecision.PROVIDER -> SettingsStatus.PROVIDER
    ModelFetchDecision.INVALID_RESPONSE -> SettingsStatus.INVALID_RESPONSE
    ModelFetchDecision.CANCELLED -> SettingsStatus.CANCELLED
    ModelFetchDecision.INTERNAL -> SettingsStatus.INTERNAL
}
