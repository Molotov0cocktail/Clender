package com.molotov.clender.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molotov.clender.R
import com.molotov.clender.domain.widget.WidgetThemeMode

@Composable
internal fun WidgetAppearancePreview(draft: WidgetConfigurationDraft) {
    val dark = when (draft.theme) {
        WidgetThemeMode.SYSTEM -> isSystemInDarkTheme()
        WidgetThemeMode.LIGHT -> false
        WidgetThemeMode.DARK -> true
    }
    val background = colorResource(
        if (dark) R.color.widget_background_dark else R.color.widget_background_light
    )
    val foreground =
        colorResource(if (dark) R.color.widget_text_dark else R.color.widget_text_light)
    val alpha = draft.opacityPercent.coerceIn(0, FULL_OPACITY) / FULL_OPACITY.toFloat()
    Text(
        stringResource(R.string.widget_opacity_explanation),
        modifier = Modifier.testTag("widget_opacity_explanation"),
        style = MaterialTheme.typography.bodySmall
    )
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(background.copy(alpha = alpha))
                .padding(12.dp)
                .testTag("widget_opacity_preview")
        ) {
            Text(
                stringResource(R.string.widget_opacity_preview_value, draft.opacityPercent),
                color = foreground,
                modifier = Modifier.testTag("widget_opacity_preview_value"),
                fontSize = draft.fontSizeSp.sp
            )
            Text(
                stringResource(R.string.widget_opacity_preview_event),
                color = foreground,
                fontSize = draft.fontSizeSp.sp
            )
        }
    }
}

private const val FULL_OPACITY = 100
