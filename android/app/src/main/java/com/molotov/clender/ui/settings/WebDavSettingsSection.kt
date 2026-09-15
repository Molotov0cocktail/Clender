package com.molotov.clender.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.molotov.clender.R

/**
 * T46-C2b WebDAV settings section: enabled switch, HTTPS URL, username, masked
 * password with configured presence, explicit password removal, Save / Test
 * Connection / Sync Now and bounded connection/sync status lines.
 */
@Composable
internal fun WebDavSettingsSection(
    state: SettingsUiState,
    operationActive: Boolean,
    actions: SettingsActions
) {
    var secretText by remember { mutableStateOf("") }
    LaunchedEffect(state.dirty, state.webDavPasswordRemovePending) {
        if (!state.dirty || state.webDavPasswordRemovePending) secretText = ""
    }
    Text(
        stringResource(R.string.settings_section_webdav),
        style = MaterialTheme.typography.titleLarge
    )
    SettingsGroupCard("settings_group_webdav_connection") {
        SettingsGroupTitle(R.string.settings_group_webdav_connection)
        WebDavConnectionSettings(state, operationActive, actions, secretText) { secretText = it }
    }
    SettingsGroupCard("settings_group_webdav_operations") {
        SettingsGroupTitle(R.string.settings_group_webdav_operations)
        WebDavStatusBanner(state)
        WebDavOperationButtons(operationActive, actions)
    }
}

@Composable
private fun WebDavConnectionSettings(
    state: SettingsUiState,
    operationActive: Boolean,
    actions: SettingsActions,
    secretText: String,
    onSecretTextChange: (String) -> Unit
) {
    WebDavEnabledRow(state, operationActive, actions)
    SettingsTextField(
        value = state.webDav.url,
        onValueChange = { actions.onUpdateWebDav(state.webDav.copy(url = it)) },
        options = SettingsFieldOptions(
            R.string.settings_webdav_url,
            "settings_webdav_url",
            !operationActive
        )
    )
    SettingsTextField(
        value = state.webDav.username,
        onValueChange = { actions.onUpdateWebDav(state.webDav.copy(username = it)) },
        options = SettingsFieldOptions(
            R.string.settings_webdav_username,
            "settings_webdav_username",
            !operationActive
        )
    )
    WebDavPasswordField(
        state,
        operationActive,
        actions,
        secretText,
        onSecretTextChange
    )
    if (state.webDavPasswordConfigured) {
        OutlinedButton(
            onClick = actions.onRequestRemoveWebDavPassword,
            enabled = !operationActive,
            modifier = Modifier.taggedTarget("settings_webdav_remove_password")
        ) {
            Text(stringResource(R.string.settings_webdav_remove_password))
        }
    }
}

@Composable
private fun WebDavEnabledRow(
    state: SettingsUiState,
    operationActive: Boolean,
    actions: SettingsActions
) {
    val label = stringResource(R.string.settings_webdav_enabled)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("settings_webdav_enabled")
            .semantics(mergeDescendants = true) {
                contentDescription = label
            }
            .toggleable(
                value = state.webDav.enabled,
                enabled = !operationActive,
                role = Role.Switch,
                onValueChange = { actions.onUpdateWebDav(state.webDav.copy(enabled = it)) }
            ),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(
            checked = state.webDav.enabled,
            enabled = !operationActive,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

@Composable
private fun WebDavPasswordField(
    state: SettingsUiState,
    operationActive: Boolean,
    actions: SettingsActions,
    secretText: String,
    onSecretTextChange: (String) -> Unit
) {
    OutlinedTextField(
        value = secretText,
        onValueChange = { value ->
            onSecretTextChange(value)
            actions.onWebDavSecretInput(value.toCharArray())
        },
        label = {
            Text(
                stringResource(
                    if (state.webDavPasswordConfigured) {
                        R.string.settings_webdav_password_configured
                    } else {
                        R.string.settings_webdav_password
                    }
                )
            )
        },
        enabled = !operationActive,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fieldModifier("settings_webdav_password")
    )
}

@Composable
private fun WebDavOperationButtons(operationActive: Boolean, actions: SettingsActions) {
    androidx.compose.foundation.layout.Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = actions.onSaveWebDav,
            enabled = !operationActive,
            modifier = Modifier
                .fillMaxWidth()
                .taggedTarget("settings_webdav_save")
        ) {
            Text(stringResource(R.string.settings_webdav_save))
        }
        OutlinedButton(
            onClick = actions.onTestWebDavConnection,
            enabled = !operationActive,
            modifier = Modifier
                .fillMaxWidth()
                .taggedTarget("settings_webdav_test_connection")
        ) {
            Text(stringResource(R.string.settings_webdav_test_connection))
        }
        OutlinedButton(
            onClick = actions.onSyncWebDavNow,
            enabled = !operationActive,
            modifier = Modifier
                .fillMaxWidth()
                .taggedTarget("settings_webdav_sync_now")
        ) {
            Text(stringResource(R.string.settings_webdav_sync_now))
        }
    }
}

@Composable
private fun WebDavStatusBanner(state: SettingsUiState) {
    val connectionResult = state.connectionResult
    if (connectionResult != null) {
        Text(
            text = stringResource(connectionResult.statusResource()),
            modifier = Modifier
                .testTag("settings_webdav_connection_status")
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
    val syncNowResult = state.syncNowResult
    Text(
        text = if (syncNowResult != null) {
            stringResource(syncNowResult.syncStatusResource())
        } else {
            runtimeStatusText(state.webDavSyncStatus)
        },
        modifier = Modifier
            .testTag("settings_webdav_sync_status")
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Composable
private fun runtimeStatusText(status: WebDavSyncStatusUi): String = when (status) {
    is WebDavSyncStatusUi.Running -> if (status.pending) {
        stringResource(R.string.settings_webdav_sync_running_pending)
    } else {
        stringResource(R.string.settings_webdav_sync_running)
    }

    is WebDavSyncStatusUi.Success -> if (status.uploaded) {
        stringResource(R.string.settings_webdav_sync_success_uploaded, status.eventCount)
    } else {
        stringResource(R.string.settings_webdav_sync_success_noop, status.eventCount)
    }

    else -> stringResource(status.runtimeStatusResource())
}
