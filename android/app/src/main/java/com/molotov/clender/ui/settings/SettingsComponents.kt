package com.molotov.clender.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
internal fun SettingsGroupCard(
    tag: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(tag),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
internal fun SettingsGroupTitle(@StringRes label: Int) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
internal fun ThemeSegmentedRow(
    state: SettingsUiState,
    operationActive: Boolean,
    actions: SettingsActions
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThemeMode.entries.forEach { theme ->
            val selected = state.appearance.themeMode == theme
            val onClick = {
                actions.onAppearanceChange(state.appearance.copy(themeMode = theme))
            }
            val modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("settings_theme_${theme.name.lowercase()}")
            val contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            if (selected) {
                Button(
                    onClick = onClick,
                    enabled = !operationActive,
                    modifier = modifier,
                    contentPadding = contentPadding
                ) {
                    Text(stringResource(theme.labelResource()), maxLines = 2)
                }
            } else {
                OutlinedButton(
                    onClick = onClick,
                    enabled = !operationActive,
                    modifier = modifier,
                    contentPadding = contentPadding
                ) {
                    Text(stringResource(theme.labelResource()), maxLines = 2)
                }
            }
        }
    }
}

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
