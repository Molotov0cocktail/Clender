package com.molotov.clender.ui.widget

// Shared configuration controls are colocated with their slider specification.

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.domain.widget.WidgetThemeMode
import kotlin.math.roundToInt

internal data class ConfigurationSliderSpec(
    val label: String,
    val value: Int,
    val range: IntRange,
    val suffix: String,
    val tag: String
)

@Composable
internal fun TimeButton(
    label: String,
    value: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = label
                stateDescription = value
            }
            .testTag(tag)
    ) {
        Text("$label: $value")
    }
}

@Composable
internal fun ConfigurationSlider(
    spec: ConfigurationSliderSpec,
    enabled: Boolean,
    onChange: (Int) -> Unit
) {
    Column {
        Text("${spec.label}: ${spec.value}${spec.suffix}")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = spec.label
                    stateDescription = "${spec.value}${spec.suffix}"
                    if (!enabled) disabled()
                }
                .testTag(spec.tag),
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value = spec.value.toFloat(),
                onValueChange = { onChange(it.roundToInt().coerceIn(spec.range)) },
                valueRange = spec.range.first.toFloat()..spec.range.last.toFloat(),
                steps = (spec.range.last - spec.range.first - 1).coerceAtLeast(0),
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun ThemeChoice(
    theme: WidgetThemeMode,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelected: (WidgetThemeMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { onSelected(theme) }
            )
            .testTag("widget_config_theme_${theme.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label)
    }
}

internal fun WidgetThemeMode.labelResource(): Int = when (this) {
    WidgetThemeMode.SYSTEM -> R.string.widget_config_theme_system
    WidgetThemeMode.LIGHT -> R.string.widget_config_theme_light
    WidgetThemeMode.DARK -> R.string.widget_config_theme_dark
}
