package com.molotov.clender.app

import com.molotov.clender.app.settings.WebDavConnectionDecision
import com.molotov.clender.app.settings.WebDavSaveDecision
import com.molotov.clender.app.settings.WebDavSyncNowDecision
import com.molotov.clender.app.sync.SyncFailureCode
import com.molotov.clender.app.sync.SyncState
import com.molotov.clender.data.settings.WebDavPasswordMutation
import com.molotov.clender.data.settings.WebDavPreferences
import com.molotov.clender.ui.settings.WebDavSettingsDraft
import com.molotov.clender.ui.settings.WebDavSyncFailureCodeUi
import com.molotov.clender.ui.settings.WebDavSyncStatusUi

internal fun WebDavPreferences.toDraft(): WebDavSettingsDraft = WebDavSettingsDraft(
    enabled = enabled,
    url = url,
    username = username
)

internal fun WebDavSettingsDraft.toWebDavPreferences(): WebDavPreferences =
    WebDavPreferences(enabled, url, username)

internal fun com.molotov.clender.ui.settings.ApiKeyMutation.toWebDavMutation(
    password: CharArray?
): WebDavPasswordMutation? = when (this) {
    com.molotov.clender.ui.settings.ApiKeyMutation.KEEP -> WebDavPasswordMutation.Keep

    com.molotov.clender.ui.settings.ApiKeyMutation.REPLACE ->
        password?.let(WebDavPasswordMutation::Replace)

    com.molotov.clender.ui.settings.ApiKeyMutation.REMOVE -> WebDavPasswordMutation.Remove
}

internal fun WebDavSaveDecision.toUi(): com.molotov.clender.ui.settings.WebDavSaveDecision =
    com.molotov.clender.ui.settings.WebDavSaveDecision.valueOf(name)

internal fun WebDavConnectionDecision.toUi() =
    com.molotov.clender.ui.settings.WebDavConnectionDecision.valueOf(name)

internal fun WebDavSyncNowDecision.toUi() =
    com.molotov.clender.ui.settings.WebDavSyncNowDecision.valueOf(name)

internal fun SyncState.toUiStatus(): WebDavSyncStatusUi = when (this) {
    SyncState.Disabled -> WebDavSyncStatusUi.Disabled

    SyncState.Unconfigured -> WebDavSyncStatusUi.Unconfigured

    SyncState.Idle -> WebDavSyncStatusUi.Idle

    is SyncState.Running -> WebDavSyncStatusUi.Running(pending = pending)

    is SyncState.Success -> WebDavSyncStatusUi.Success(
        uploaded = uploaded,
        localChanged = localChanged,
        eventCount = eventCount
    )

    is SyncState.Failed -> WebDavSyncStatusUi.Failed(code.toUiCode())
}

internal fun SyncFailureCode.toUiCode(): WebDavSyncFailureCodeUi =
    WebDavSyncFailureCodeUi.valueOf(name)
