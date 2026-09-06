package com.molotov.clender.domain.widget

import java.time.LocalTime

enum class WidgetThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

data class WidgetConfiguration(
    val appWidgetId: Int,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val opacityPercent: Int,
    val fontSizeSp: Int,
    val theme: WidgetThemeMode
) {
    init {
        require(appWidgetId > MIN_WIDGET_ID_EXCLUSIVE) { "Widget ID must be positive" }
        require(startTime.second == ZERO && startTime.nano == ZERO) {
            "Widget start time must have minute precision"
        }
        require(endTime.second == ZERO && endTime.nano == ZERO) {
            "Widget end time must have minute precision"
        }
        require(startTime < endTime) { "Widget time range must be increasing" }
        require(opacityPercent in OPACITY_RANGE) { "Widget opacity is out of range" }
        require(fontSizeSp in FONT_RANGE) { "Widget font size is out of range" }
    }

    companion object {
        fun defaults(appWidgetId: Int, widgetFontSizeSp: Int): WidgetConfiguration =
            WidgetConfiguration(
                appWidgetId = appWidgetId,
                startTime = LocalTime.of(DEFAULT_START_HOUR, ZERO),
                endTime = LocalTime.of(DEFAULT_END_HOUR, ZERO),
                opacityPercent = DEFAULT_OPACITY_PERCENT,
                fontSizeSp = widgetFontSizeSp.takeIf { it in FONT_RANGE } ?: DEFAULT_FONT_SIZE_SP,
                theme = WidgetThemeMode.SYSTEM
            )
    }
}

private const val ZERO = 0
private const val MIN_WIDGET_ID_EXCLUSIVE = 0
private const val DEFAULT_START_HOUR = 8
private const val DEFAULT_END_HOUR = 22
private const val DEFAULT_OPACITY_PERCENT = 100
private const val DEFAULT_FONT_SIZE_SP = 13
private const val MIN_FONT_SIZE_SP = 8
private const val MAX_FONT_SIZE_SP = 20
private const val MIN_OPACITY_PERCENT = 0
private const val MAX_OPACITY_PERCENT = 100

private val FONT_RANGE = MIN_FONT_SIZE_SP..MAX_FONT_SIZE_SP
private val OPACITY_RANGE = MIN_OPACITY_PERCENT..MAX_OPACITY_PERCENT
