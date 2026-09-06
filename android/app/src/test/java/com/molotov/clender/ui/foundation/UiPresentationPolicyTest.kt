package com.molotov.clender.ui.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPresentationPolicyTest {
    @Test
    fun themeAndIndependentFontSizesRestoreWithStrictSafeDefaults() {
        assertEquals(
            listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
            ThemeMode.entries
        )
        assertEquals(8, FontSizePolicy.normalize(8))
        assertEquals(20, FontSizePolicy.normalize(20))
        assertEquals(13, FontSizePolicy.normalize(7))
        assertEquals(13, FontSizePolicy.normalize(21))
        assertEquals(13, FontSizePolicy.normalize(null))

        assertEquals(
            AppearanceUiState(ThemeMode.DARK, appFontSizeSp = 20, widgetFontSizeSp = 8),
            AppearanceUiState.fromPersisted("dark", appFontSizeSp = 20, widgetFontSizeSp = 8)
        )
        assertEquals(
            AppearanceUiState(ThemeMode.SYSTEM, appFontSizeSp = 13, widgetFontSizeSp = 13),
            AppearanceUiState.fromPersisted("unknown", appFontSizeSp = -1, widgetFontSizeSp = 200)
        )
    }

    @Test
    fun everyInteractiveSemanticTargetHasStableUniqueLabelAndTouchContract() {
        val specs = UiSemantics.requiredTargets

        assertEquals(
            setOf(
                "open_navigation_drawer",
                "navigate_back",
                "calendar_previous",
                "calendar_next",
                "calendar_mode",
                "calendar_date",
                "calendar_timeline_event",
                "calendar_overflow",
                "create_event",
                "event_item",
                "event_edit",
                "event_delete",
                "event_save",
                "ai_conversation",
                "ai_thinking",
                "ai_composer",
                "send_ai_message",
                "settings_section",
                "settings_field",
                "settings_action",
                "webdav_enabled",
                "webdav_status",
                "about_content"
            ),
            specs.mapTo(linkedSetOf(), SemanticTargetSpec::id)
        )
        assertEquals(specs.size, specs.map(SemanticTargetSpec::testTag).toSet().size)
        assertTrue(specs.all { it.labelKey.isNotBlank() && it.minimumTouchTargetDp >= 48 })
    }

    @Test
    fun adaptivePolicyUsesStablePhoneTabletBreakpointsAndModalDrawer() {
        assertEquals(WindowWidthClass.COMPACT, AdaptiveLayoutPolicy.forWidthDp(599).widthClass)
        assertEquals(WindowWidthClass.MEDIUM, AdaptiveLayoutPolicy.forWidthDp(600).widthClass)
        assertEquals(WindowWidthClass.MEDIUM, AdaptiveLayoutPolicy.forWidthDp(839).widthClass)
        assertEquals(WindowWidthClass.EXPANDED, AdaptiveLayoutPolicy.forWidthDp(840).widthClass)
        assertEquals(PaneLayout.SINGLE, AdaptiveLayoutPolicy.forWidthDp(599).aiPaneLayout)
        assertEquals(PaneLayout.TWO, AdaptiveLayoutPolicy.forWidthDp(600).aiPaneLayout)
        assertTrue(
            listOf(360, 600, 840, 1200).all {
                AdaptiveLayoutPolicy.forWidthDp(it).navigationChrome ==
                    NavigationChrome.MODAL_DRAWER
            }
        )
        assertThrows(IllegalArgumentException::class.java) {
            AdaptiveLayoutPolicy.forWidthDp(0)
        }
    }
}
