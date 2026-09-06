package com.molotov.clender.ui.widget

import com.molotov.clender.domain.widget.WidgetThemeMode

data class WidgetConfigurationActions(
    val onRetry: () -> Unit,
    val onStartTimeClick: () -> Unit,
    val onEndTimeClick: () -> Unit,
    val onOpacityChange: (Int) -> Unit,
    val onFontSizeChange: (Int) -> Unit,
    val onThemeChange: (WidgetThemeMode) -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
    val onConfirmDiscard: () -> Unit,
    val onKeepEditing: () -> Unit
)
