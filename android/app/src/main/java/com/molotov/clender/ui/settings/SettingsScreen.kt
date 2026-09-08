package com.molotov.clender.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.ui.event.AlertPermissionSection
import com.molotov.clender.ui.foundation.ThemeMode

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier
) {
    val saveableStateHolder = rememberSaveableStateHolder()
    val operationActive = state.status in setOf(
        SettingsStatus.LOADING,
        SettingsStatus.SAVING,
        SettingsStatus.TESTING,
        SettingsStatus.FETCHING
    )
    Column(modifier = modifier) {
        SettingsSectionTabs(state.section, actions.onSectionChange)
        saveableStateHolder.SaveableStateProvider("settings:${state.section.name}") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_content")
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (state.section) {
                    SettingsSection.APPLICATION -> ApplicationSettingsSection(
                        state,
                        operationActive,
                        actions
                    )

                    SettingsSection.AI -> AiSettingsSection(state, operationActive, actions)

                    SettingsSection.WEBDAV -> WebDavSettingsSection(state, operationActive, actions)
                }
            }
        }
    }
    SettingsConfirmationDialogs(state, actions)
}

@Composable
internal fun SettingsConfirmationDialogs(state: SettingsUiState, actions: SettingsActions) {
    if (state.removeKeyConfirmation) {
        ConfirmationDialog(
            spec = ConfirmationSpec(
                R.string.settings_remove_key_title,
                R.string.settings_remove_key_message,
                "settings_confirm_remove_key",
                "settings_cancel_remove_key"
            ),
            onConfirm = actions.onConfirmRemoveKey,
            onCancel = actions.onCancelRemoveKey
        )
    }
    if (state.webDavPasswordRemoveConfirmation) {
        ConfirmationDialog(
            spec = ConfirmationSpec(
                R.string.settings_webdav_remove_title,
                R.string.settings_webdav_remove_message,
                "settings_confirm_remove_webdav_password",
                "settings_cancel_remove_webdav_password"
            ),
            onConfirm = actions.onConfirmRemoveWebDavPassword,
            onCancel = actions.onCancelRemoveWebDavPassword
        )
    }
    if (state.discardConfirmation) {
        ConfirmationDialog(
            spec = ConfirmationSpec(
                R.string.settings_discard_title,
                R.string.settings_discard_message,
                "settings_discard_confirm",
                "settings_discard_cancel"
            ),
            onConfirm = actions.onConfirmDiscard,
            onCancel = actions.onCancelDiscard
        )
    }
}

@Composable
private fun SettingsSectionTabs(selected: SettingsSection, onSelected: (SettingsSection) -> Unit) {
    TabRow(selectedTabIndex = selected.ordinal) {
        Tab(
            selected = selected == SettingsSection.APPLICATION,
            onClick = { onSelected(SettingsSection.APPLICATION) },
            modifier = Modifier.taggedTarget("settings_section_application"),
            text = { Text(stringResource(R.string.settings_section_application)) }
        )
        Tab(
            selected = selected == SettingsSection.AI,
            onClick = { onSelected(SettingsSection.AI) },
            modifier = Modifier.taggedTarget("settings_section_ai"),
            text = { Text(stringResource(R.string.settings_section_ai)) }
        )
        Tab(
            selected = selected == SettingsSection.WEBDAV,
            onClick = { onSelected(SettingsSection.WEBDAV) },
            modifier = Modifier.taggedTarget("settings_section_webdav"),
            text = { Text(stringResource(R.string.settings_section_webdav)) }
        )
    }
}

@Composable
private fun ApplicationSettingsSection(
    state: SettingsUiState,
    operationActive: Boolean,
    actions: SettingsActions
) {
    val widgetPreviewDescription = stringResource(R.string.settings_widget_preview_semantics)
    Text(
        stringResource(R.string.settings_section_application),
        style = MaterialTheme.typography.titleLarge
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { theme ->
            OutlinedButton(
                onClick = { actions.onAppearanceChange(state.appearance.copy(themeMode = theme)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .taggedTarget("settings_theme_${theme.name.lowercase()}"),
                enabled = !operationActive
            ) {
                Text(stringResource(theme.labelResource()))
            }
        }
    }
    SettingsTextField(
        value = state.appearance.appFontSize,
        onValueChange = {
            actions.onAppearanceChange(state.appearance.copy(appFontSize = it))
        },
        options = SettingsFieldOptions(
            R.string.settings_app_font_size,
            "settings_app_font",
            !operationActive,
            SettingsValidationError.APP_FONT_SIZE in state.validationErrors
        )
    )
    SettingsTextField(
        value = state.appearance.widgetFontSize,
        onValueChange = {
            actions.onAppearanceChange(state.appearance.copy(widgetFontSize = it))
        },
        options = SettingsFieldOptions(
            R.string.settings_widget_font_size,
            "settings_widget_font",
            !operationActive,
            SettingsValidationError.WIDGET_FONT_SIZE in state.validationErrors
        )
    )
    Text(
        text = stringResource(R.string.settings_widget_preview, state.widgetFontSizeSp),
        modifier = Modifier
            .testTag("settings_widget_preview")
            .semantics { contentDescription = widgetPreviewDescription }
    )
    Button(
        onClick = actions.onSaveAppearance,
        enabled = !operationActive,
        modifier = Modifier.taggedTarget("settings_save_application")
    ) {
        Text(stringResource(R.string.settings_save_application))
    }
    BackgroundSettingsSection()
    AlertPermissionSection()
}

@Composable
private fun AiSettingsSection(
    state: SettingsUiState,
    operationActive: Boolean,
    actions: SettingsActions
) {
    var secretText by remember {
        val draft = state.secretInput.copyChars()
        try {
            mutableStateOf(String(draft))
        } finally {
            draft.fill('\u0000')
        }
    }
    val secretInputEmpty = state.secretInput.isEmpty()
    LaunchedEffect(secretInputEmpty, state.removeKeyPending) {
        if (secretInputEmpty || state.removeKeyPending) secretText = ""
    }
    Text(stringResource(R.string.settings_section_ai), style = MaterialTheme.typography.titleLarge)
    AiConnectionSettings(state, operationActive, actions, secretText) { secretText = it }
    AiGenerationSettings(state, operationActive, actions)
    AiPromptSettings(state, operationActive, actions)
    ModelFetchStatus(state, operationActive, actions)
    AiOperationButtons(operationActive, actions)
}

@Composable
private fun AiConnectionSettings(
    state: SettingsUiState,
    operationActive: Boolean,
    actions: SettingsActions,
    secretText: String,
    onSecretTextChange: (String) -> Unit
) {
    SettingsTextField(
        state.ai.endpoint,
        { actions.onAiChange(state.ai.copy(endpoint = it)) },
        SettingsFieldOptions(
            R.string.settings_ai_endpoint,
            "settings_ai_endpoint",
            !operationActive,
            SettingsValidationError.AI_ENDPOINT in state.validationErrors
        )
    )
    OutlinedTextField(
        value = secretText,
        onValueChange = { value ->
            onSecretTextChange(value)
            actions.onSecretInput(value.toCharArray())
        },
        label = {
            Text(
                stringResource(
                    if (state.apiKeyConfigured) {
                        R.string.settings_ai_key_configured
                    } else {
                        R.string.settings_ai_key
                    }
                )
            )
        },
        enabled = !operationActive,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fieldModifier("settings_ai_key")
    )
    if (state.apiKeyConfigured) {
        OutlinedButton(
            onClick = actions.onRequestRemoveKey,
            enabled = !operationActive,
            modifier = Modifier.taggedTarget("settings_remove_key")
        ) {
            Text(stringResource(R.string.settings_remove_key))
        }
    }
    SettingsTextField(
        state.ai.model,
        { actions.onAiChange(state.ai.copy(model = it)) },
        SettingsFieldOptions(R.string.settings_ai_model, "settings_ai_model", !operationActive)
    )
}

@Composable
private fun AiGenerationSettings(
    state: SettingsUiState,
    operationActive: Boolean,
    actions: SettingsActions
) {
    SettingsTextField(
        state.ai.temperature,
        { actions.onAiChange(state.ai.copy(temperature = it)) },
        SettingsFieldOptions(
            R.string.settings_ai_temperature,
            "settings_ai_temperature",
            !operationActive,
            SettingsValidationError.AI_TEMPERATURE in state.validationErrors
        )
    )
    SettingsTextField(
        state.ai.maxOutputTokens,
        { actions.onAiChange(state.ai.copy(maxOutputTokens = it)) },
        SettingsFieldOptions(
            R.string.settings_ai_max_output,
            "settings_ai_max_output",
            !operationActive,
            SettingsValidationError.AI_MAX_OUTPUT_TOKENS in state.validationErrors
        )
    )
    SettingsTextField(
        state.ai.contextWindow,
        { actions.onAiChange(state.ai.copy(contextWindow = it)) },
        SettingsFieldOptions(
            R.string.settings_ai_context,
            "settings_ai_context",
            !operationActive,
            SettingsValidationError.AI_CONTEXT_WINDOW in state.validationErrors
        )
    )
    ThinkingToggle(state, operationActive, actions)
    ThinkingEffortSelector(state, operationActive, actions)
}

@Composable
private fun AiPromptSettings(
    state: SettingsUiState,
    operationActive: Boolean,
    actions: SettingsActions
) {
    Text(stringResource(R.string.ai_embedded_contract))
    SettingsTextField(
        state.ai.personality,
        { actions.onAiChange(state.ai.copy(personality = it)) },
        SettingsFieldOptions(
            R.string.settings_ai_personality,
            "settings_ai_personality",
            !operationActive,
            singleLine = false
        )
    )
}

@Composable
private fun AiOperationButtons(operationActive: Boolean, actions: SettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = actions.onFetchModels,
            enabled = !operationActive,
            modifier = Modifier
                .fillMaxWidth()
                .taggedTarget("settings_fetch_models")
        ) {
            Text(stringResource(R.string.settings_fetch_models))
        }
        Button(
            onClick = actions.onSaveAi,
            enabled = !operationActive,
            modifier = Modifier
                .fillMaxWidth()
                .taggedTarget("settings_save_ai")
        ) {
            Text(stringResource(R.string.settings_save_ai))
        }
    }
}

@Composable
private fun ModelFetchStatus(
    state: SettingsUiState,
    operationActive: Boolean,
    actions: SettingsActions
) {
    when (state.status) {
        SettingsStatus.FETCHING -> CircularProgressIndicator(
            modifier = Modifier
                .testTag("settings_model_progress")
                .semantics { liveRegion = LiveRegionMode.Polite }
        )

        SettingsStatus.MODELS_EMPTY -> Text(
            stringResource(R.string.settings_models_empty),
            modifier = Modifier
                .testTag("settings_models_empty")
                .semantics { liveRegion = LiveRegionMode.Polite }
        )

        SettingsStatus.BUSY,
        SettingsStatus.UNCONFIGURED,
        SettingsStatus.TIMEOUT,
        SettingsStatus.NETWORK,
        SettingsStatus.PROVIDER,
        SettingsStatus.INVALID_RESPONSE,
        SettingsStatus.CANCELLED,
        SettingsStatus.INTERNAL,
        SettingsStatus.SAVE_FAILED -> Text(
            stringResource(R.string.settings_models_error),
            modifier = Modifier
                .testTag("settings_models_error")
                .semantics { liveRegion = LiveRegionMode.Polite }
        )

        SettingsStatus.INACTIVE,
        SettingsStatus.LOADING,
        SettingsStatus.READY,
        SettingsStatus.SAVING,
        SettingsStatus.TESTING,
        SettingsStatus.VALIDATION_FAILED -> Unit
    }
    ModelCapabilityHint(state)
    state.models.forEach { model ->
        OutlinedButton(
            onClick = { actions.onAiChange(state.ai.copy(model = model)) },
            enabled = !operationActive,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(model)
        }
    }
}
