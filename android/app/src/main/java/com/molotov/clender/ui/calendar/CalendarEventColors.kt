// ARGB literals are the reviewed palette values, not algorithmic constants.
@file:Suppress("MagicNumber")

package com.molotov.clender.ui.calendar

import androidx.compose.ui.graphics.Color

internal const val CALENDAR_DARK_BACKGROUND_THRESHOLD = 0.5f

internal data class CalendarEventColors(
    val background: Color,
    val foreground: Color,
    val accent: Color
)

private val lightEventColors = listOf(
    CalendarEventColors(Color(0xFFE3EFFD), Color(0xFF13385E), Color(0xFF397AB8)),
    CalendarEventColors(Color(0xFFE1F2E9), Color(0xFF174D36), Color(0xFF398260)),
    CalendarEventColors(Color(0xFFFCF0D6), Color(0xFF5B3D11), Color(0xFFA37728)),
    CalendarEventColors(Color(0xFFEEE5F8), Color(0xFF493060), Color(0xFF8660AC)),
    CalendarEventColors(Color(0xFFFAE3E9), Color(0xFF602B40), Color(0xFFAC5275)),
    CalendarEventColors(Color(0xFFDCF1F3), Color(0xFF164952), Color(0xFF36858E))
)

private val darkEventColors = listOf(
    CalendarEventColors(Color(0xFF1F3B56), Color(0xFFE1EEFF), Color(0xFF90C1F2)),
    CalendarEventColors(Color(0xFF214637), Color(0xFFDCF6E7), Color(0xFF88CEA6)),
    CalendarEventColors(Color(0xFF514020), Color(0xFFFFF0CC), Color(0xFFE5BF75)),
    CalendarEventColors(Color(0xFF423153), Color(0xFFF0E2FF), Color(0xFFC8A2EA)),
    CalendarEventColors(Color(0xFF542D3C), Color(0xFFFFE2EC), Color(0xFFEAA0BB)),
    CalendarEventColors(Color(0xFF1D444A), Color(0xFFDCF7FA), Color(0xFF82CFD7))
)

internal fun calendarEventColors(eventId: Long, dark: Boolean): CalendarEventColors {
    val palette = if (dark) darkEventColors else lightEventColors
    return palette[Math.floorMod(eventId, palette.size.toLong()).toInt()]
}
