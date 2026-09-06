package com.molotov.clender.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.ui.foundation.ThemeMode

@Composable
internal fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    options: SettingsFieldOptions
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(options.label)) },
        enabled = options.enabled,
        isError = options.isError,
        singleLine = options.singleLine,
        modifier = Modifier.fieldModifier(options.tag)
    )
}

@Composable
internal fun ConfirmationDialog(
    spec: ConfirmationSpec,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(spec.title)) },
        text = { Text(stringResource(spec.message)) },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.taggedTarget(spec.confirmTag)) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel, modifier = Modifier.taggedTarget(spec.cancelTag)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
internal fun ThinkingToggle(
    state: SettingsUiState,
    operationActive: Boolean,
    actions: SettingsActions
) {
    val label = stringResource(R.string.settings_ai_thinking)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("settings_ai_thinking")
            .semantics(mergeDescendants = true) {
                contentDescription = label
            }
            .toggleable(
                value = state.ai.thinkingEnabled,
                enabled = !operationActive,
                role = Role.Switch,
                onValueChange = {
                    actions.onAiChange(state.ai.copy(thinkingEnabled = it))
                }
            ),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(
            checked = state.ai.thinkingEnabled,
            enabled = !operationActive,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

internal data class SettingsFieldOptions(
    @param:StringRes val label: Int,
    val tag: String,
    val enabled: Boolean,
    val isError: Boolean = false,
    val singleLine: Boolean = true
)

internal data class ConfirmationSpec(
    @param:StringRes val title: Int,
    @param:StringRes val message: Int,
    val confirmTag: String,
    val cancelTag: String
)

internal fun Modifier.fieldModifier(tag: String): Modifier = this
    .fillMaxWidth()
    .heightIn(min = 48.dp)
    .testTag(tag)

internal fun Modifier.taggedTarget(tag: String): Modifier = this
    .heightIn(min = 48.dp)
    .testTag(tag)

@StringRes
internal fun ThemeMode.labelResource(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}
