package com.molotov.clender.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
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
    val shape = RoundedCornerShape(16.dp)
    TabRow(
        selectedTabIndex = selected.ordinal,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = {},
        indicator = { positions ->
            Box(
                Modifier
                    .tabIndicatorOffset(positions[selected.ordinal])
                    .padding(horizontal = 16.dp)
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
            )
        }
    ) {
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
    SettingsGroupCard("settings_group_appearance") {
        SettingsGroupTitle(R.string.settings_group_appearance)
        ThemeSegmentedRow(state, operationActive, actions)
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
            modifier = Modifier
                .fillMaxWidth()
                .taggedTarget("settings_save_application")
        ) {
            Text(stringResource(R.string.settings_save_application))
        }
    }
    SettingsGroupCard("settings_group_background") {
        BackgroundSettingsSection()
    }
    SettingsGroupCard("settings_group_alarm_sound") {
        AlarmSoundSettingsSection()
    }
    SettingsGroupCard("settings_group_alert_permissions") {
        AlertPermissionSection()
    }
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
    Text(stringResource(R.string.settings_section_ai), style = MaterialTheme.typography.titleLarge)
    SettingsGroupCard("settings_group_ai_connection") {
        // T75: the clear effect lives in the card content scope, which is the scope
        // that reads secretText; this keeps the T49 snapshot semantics where the
        // effect key only flips when the mutable SecretInput snapshot changes.
        val secretInputEmpty = state.secretInput.isEmpty()
        LaunchedEffect(secretInputEmpty, state.removeKeyPending) {
            if (secretInputEmpty || state.removeKeyPending) secretText = ""
        }
        SettingsGroupTitle(R.string.settings_group_ai_connection)
        AiConnectionSettings(state, operationActive, actions, secretText) { secretText = it }
    }
    SettingsGroupCard("settings_group_ai_generation") {
        SettingsGroupTitle(R.string.settings_group_ai_generation)
        AiGenerationSettings(state, operationActive, actions)
    }
    SettingsGroupCard("settings_group_ai_prompt") {
        SettingsGroupTitle(R.string.settings_group_ai_prompt)
        AiPromptSettings(state, operationActive, actions)
    }
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
