package com.molotov.clender.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molotov.clender.data.settings.AppearanceSettings
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class AppearanceViewModel(appearance: Flow<AppearanceSettings>) : ViewModel() {
    val state: StateFlow<AppearanceUiState> = appearance
        .map(AppearanceSettings::toUiState)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AppearanceUiState.fromPersisted(null, null, null)
        )
}

private fun AppearanceSettings.toUiState(): AppearanceUiState = AppearanceUiState(
    themeMode = when (theme) {
        com.molotov.clender.data.settings.ThemeMode.SYSTEM -> ThemeMode.SYSTEM
        com.molotov.clender.data.settings.ThemeMode.LIGHT -> ThemeMode.LIGHT
        com.molotov.clender.data.settings.ThemeMode.DARK -> ThemeMode.DARK
    },
    appFontSizeSp = appFontSizeSp,
    widgetFontSizeSp = widgetFontSizeSp
)
