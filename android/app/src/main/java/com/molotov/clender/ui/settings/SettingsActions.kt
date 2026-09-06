package com.molotov.clender.ui.settings

data class SettingsActions(
    val onSectionChange: (SettingsSection) -> Unit,
    val onAppearanceChange: (AppearanceSettingsDraft) -> Unit,
    val onAiChange: (AiSettingsDraft) -> Unit,
    val onSecretInput: (CharArray) -> Unit,
    val onSaveAppearance: () -> Unit,
    val onSaveAi: () -> Unit,
    val onFetchModels: () -> Unit,
    val onRequestRemoveKey: () -> Unit,
    val onConfirmRemoveKey: () -> Unit,
    val onCancelRemoveKey: () -> Unit,
    val onConfirmDiscard: () -> Unit,
    val onCancelDiscard: () -> Unit,
    val onUpdateWebDav: (WebDavSettingsDraft) -> Unit = {},
    val onWebDavSecretInput: (CharArray) -> Unit = {},
    val onSaveWebDav: () -> Unit = {},
    val onTestWebDavConnection: () -> Unit = {},
    val onSyncWebDavNow: () -> Unit = {},
    val onRequestRemoveWebDavPassword: () -> Unit = {},
    val onConfirmRemoveWebDavPassword: () -> Unit = {},
    val onCancelRemoveWebDavPassword: () -> Unit = {}
)
