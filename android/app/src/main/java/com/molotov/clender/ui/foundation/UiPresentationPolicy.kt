package com.molotov.clender.ui.foundation

enum class ThemeMode(val wireValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromWire(value: String?): ThemeMode = entries.firstOrNull {
            it.wireValue == value
        } ?: SYSTEM
    }
}

object FontSizePolicy {
    const val MIN_SP: Int = 8
    const val MAX_SP: Int = 20
    const val DEFAULT_SP: Int = 13

    fun normalize(value: Int?): Int = value?.takeIf { it in MIN_SP..MAX_SP } ?: DEFAULT_SP
}

data class AppearanceUiState(
    val themeMode: ThemeMode,
    val appFontSizeSp: Int,
    val widgetFontSizeSp: Int
) {
    companion object {
        fun fromPersisted(
            themeValue: String?,
            appFontSizeSp: Int?,
            widgetFontSizeSp: Int?
        ): AppearanceUiState = AppearanceUiState(
            themeMode = ThemeMode.fromWire(themeValue),
            appFontSizeSp = FontSizePolicy.normalize(appFontSizeSp),
            widgetFontSizeSp = FontSizePolicy.normalize(widgetFontSizeSp)
        )
    }
}

data class SemanticTargetSpec(
    val id: String,
    val testTag: String,
    val labelKey: String,
    val minimumTouchTargetDp: Int
)

object UiSemantics {
    val requiredTargets: List<SemanticTargetSpec> = listOf(
        target("open_navigation_drawer"),
        target("navigate_back"),
        target("calendar_previous"),
        target("calendar_next"),
        target("calendar_mode"),
        target("calendar_date"),
        target("calendar_timeline_event"),
        target("calendar_overflow"),
        target("create_event"),
        target("event_item"),
        target("event_edit"),
        target("event_delete"),
        target("event_save"),
        target("ai_conversation"),
        target("ai_thinking"),
        target("ai_composer"),
        target("send_ai_message"),
        target("settings_section"),
        target("settings_field"),
        target("settings_action"),
        target("webdav_enabled"),
        target("webdav_status"),
        target("about_content")
    )

    private fun target(id: String): SemanticTargetSpec = SemanticTargetSpec(
        id = id,
        testTag = "clender_$id",
        labelKey = "semantics_$id",
        minimumTouchTargetDp = 48
    )
}

enum class WindowWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

enum class PaneLayout {
    SINGLE,
    TWO
}

enum class NavigationChrome {
    MODAL_DRAWER
}

data class AdaptiveLayout(
    val widthClass: WindowWidthClass,
    val aiPaneLayout: PaneLayout,
    val navigationChrome: NavigationChrome
)

object AdaptiveLayoutPolicy {
    fun forWidthDp(widthDp: Int): AdaptiveLayout {
        require(widthDp > 0) { "Window width must be positive" }
        val widthClass = when {
            widthDp < COMPACT_MAX_WIDTH_DP -> WindowWidthClass.COMPACT
            widthDp < MEDIUM_MAX_WIDTH_DP -> WindowWidthClass.MEDIUM
            else -> WindowWidthClass.EXPANDED
        }
        return AdaptiveLayout(
            widthClass = widthClass,
            aiPaneLayout = if (widthClass == WindowWidthClass.COMPACT) {
                PaneLayout.SINGLE
            } else {
                PaneLayout.TWO
            },
            navigationChrome = NavigationChrome.MODAL_DRAWER
        )
    }

    private const val COMPACT_MAX_WIDTH_DP = 600
    private const val MEDIUM_MAX_WIDTH_DP = 840
}
