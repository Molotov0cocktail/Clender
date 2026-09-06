package com.molotov.clender.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.domain.widget.WidgetThemeMode

@Composable
fun WidgetConfigurationScreen(
    state: WidgetConfigurationUiState,
    actions: WidgetConfigurationActions,
    showDiscardConfirmation: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.widget_config_title),
            modifier = Modifier.testTag("widget_config_title"),
            style = MaterialTheme.typography.headlineSmall
        )
        when (state) {
            WidgetConfigurationUiState.Loading -> LoadingContent()

            is WidgetConfigurationUiState.LoadFailed -> FailureContent(
                message = stringResource(R.string.widget_config_load_failed),
                actions = actions
            )

            is WidgetConfigurationUiState.Content -> ConfigurationForm(
                draft = state.draft,
                enabled = !state.saving,
                status = when {
                    state.saving -> stringResource(R.string.widget_config_saving)

                    state.validation == WidgetConfigurationError.INVALID_TIME_RANGE ->
                        stringResource(R.string.widget_config_invalid_time)

                    else -> null
                },
                actions = actions
            )

            is WidgetConfigurationUiState.SaveFailed -> {
                ConfigurationForm(
                    draft = state.draft,
                    enabled = true,
                    status = stringResource(R.string.widget_config_save_failed),
                    actions = actions
                )
                RetryButton(actions.onRetry)
            }
        }
    }
    if (showDiscardConfirmation) {
        WidgetDiscardConfirmationDialog(
            onDiscard = actions.onConfirmDiscard,
            onKeepEditing = actions.onKeepEditing
        )
    }
}

@Composable
private fun LoadingContent() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("widget_config_status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.widget_config_loading))
    }
}

@Composable
private fun FailureContent(message: String, actions: WidgetConfigurationActions) {
    StatusText(message)
    RetryButton(actions.onRetry)
}

@Composable
private fun RetryButton(onRetry: () -> Unit) {
    TextButton(
        onClick = onRetry,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("widget_config_retry")
    ) {
        Text(stringResource(R.string.action_retry))
    }
}

@Composable
private fun ConfigurationForm(
    draft: WidgetConfigurationDraft,
    enabled: Boolean,
    status: String?,
    actions: WidgetConfigurationActions
) {
    TimeRangeControls(draft, enabled, actions)
    AppearanceControls(draft, enabled, actions)
    ThemeControls(draft.theme, enabled, actions.onThemeChange)
    if (status != null) {
        StatusText(status)
    }
    FormActions(enabled, actions)
}

@Composable
private fun TimeRangeControls(
    draft: WidgetConfigurationDraft,
    enabled: Boolean,
    actions: WidgetConfigurationActions
) {
    TimeButton(
        label = stringResource(R.string.widget_config_start_time),
        value = formatWidgetTime(draft.startTime),
        tag = "widget_config_start_time",
        enabled = enabled,
        onClick = actions.onStartTimeClick
    )
    TimeButton(
        label = stringResource(R.string.widget_config_end_time),
        value = formatWidgetTime(draft.endTime),
        tag = "widget_config_end_time",
        enabled = enabled,
        onClick = actions.onEndTimeClick
    )
}

@Composable
private fun AppearanceControls(
    draft: WidgetConfigurationDraft,
    enabled: Boolean,
    actions: WidgetConfigurationActions
) {
    ConfigurationSlider(
        spec = ConfigurationSliderSpec(
            label = stringResource(R.string.widget_config_opacity),
            value = draft.opacityPercent,
            range = 0..100,
            suffix = "%",
            tag = "widget_config_opacity"
        ),
        enabled = enabled,
        onChange = actions.onOpacityChange
    )
    ConfigurationSlider(
        spec = ConfigurationSliderSpec(
            label = stringResource(R.string.widget_config_font_size),
            value = draft.fontSizeSp,
            range = 8..20,
            suffix = "sp",
            tag = "widget_config_font_size"
        ),
        enabled = enabled,
        onChange = actions.onFontSizeChange
    )
}

@Composable
private fun ThemeControls(
    selectedTheme: WidgetThemeMode,
    enabled: Boolean,
    onThemeChange: (WidgetThemeMode) -> Unit
) {
    Text(
        text = stringResource(R.string.widget_config_theme),
        style = MaterialTheme.typography.titleMedium
    )
    Column(Modifier.selectableGroup()) {
        WidgetThemeMode.entries.forEach { theme ->
            ThemeChoice(
                theme = theme,
                label = stringResource(theme.labelResource()),
                selected = selectedTheme == theme,
                enabled = enabled,
                onSelected = onThemeChange
            )
        }
    }
}

@Composable
private fun FormActions(enabled: Boolean, actions: WidgetConfigurationActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = actions.onCancel,
            enabled = enabled,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("widget_config_cancel")
        ) {
            Text(stringResource(R.string.action_cancel))
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = actions.onSave,
            enabled = enabled,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("widget_config_save")
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
private fun StatusText(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("widget_config_status")
    )
}
