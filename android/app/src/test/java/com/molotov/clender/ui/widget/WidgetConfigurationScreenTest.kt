package com.molotov.clender.ui.widget

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.domain.widget.WidgetThemeMode
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetConfigurationScreenTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val composeHost = RobolectricComposeHost()

    @Before
    fun startHost() = composeHost.start()

    @After
    fun closeHost() = composeHost.close()

    @Test
    fun contentExposesAllStableControlsWithReachableTargetsAndRadioRoles() {
        setScreen(content())

        listOf(
            "widget_config_start_time",
            "widget_config_end_time",
            "widget_config_theme_system",
            "widget_config_theme_light",
            "widget_config_theme_dark",
            "widget_config_save",
            "widget_config_cancel"
        ).forEach(::reachable)
        listOf("widget_config_opacity", "widget_config_font_size").forEach(::reachableSlider)
        assertRadio("widget_config_theme_system", selected = true)
        assertRadio("widget_config_theme_light", selected = false)
        assertRadio("widget_config_theme_dark", selected = false)
    }

    @Test
    fun boundaryValuesAndAllThreeThemesRemainObservableAndInteractive() {
        val selectedThemes = mutableListOf<WidgetThemeMode>()
        listOf(
            draft(opacity = 0, fontSize = 8, theme = WidgetThemeMode.SYSTEM),
            draft(opacity = 100, fontSize = 20, theme = WidgetThemeMode.LIGHT),
            draft(opacity = 100, fontSize = 20, theme = WidgetThemeMode.DARK)
        ).forEach { value ->
            setScreen(
                content(value),
                actions(onThemeChange = selectedThemes::add)
            )
            val opacity = composeRule.onNodeWithTag("widget_config_opacity")
                .performScrollTo().fetchSemanticsNode().config.toString()
            val font = composeRule.onNodeWithTag("widget_config_font_size")
                .performScrollTo().fetchSemanticsNode().config.toString()
            assertTrue(opacity.contains(value.opacityPercent.toString()))
            assertTrue(font.contains(value.fontSizeSp.toString()))
            composeRule.onNodeWithTag("widget_config_theme_${value.theme.name.lowercase()}")
                .performScrollTo().assertIsSelected()
        }

        composeRule.onNodeWithTag("widget_config_theme_system").performScrollTo().performClick()
        composeRule.onNodeWithTag("widget_config_theme_light").performScrollTo().performClick()
        composeRule.onNodeWithTag("widget_config_theme_dark").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf(WidgetThemeMode.SYSTEM, WidgetThemeMode.LIGHT, WidgetThemeMode.DARK),
                selectedThemes
            )
        }
    }

    @Test
    fun timePickerUsesMinutePrecisionAndConfirmCancelTargets() {
        var chosen: LocalTime? = null
        var dismissed = 0
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                WidgetTimePickerDialog(
                    selectedTime = LocalTime.of(8, 7, 59, 999_999_999),
                    onTimeSelected = { chosen = it },
                    onDismiss = { dismissed += 1 }
                )
            }
        }
        composeRule.waitForIdle()

        reachableDialogAction("widget_config_time_picker_cancel")
        reachableDialogAction("widget_config_time_picker_confirm").performClick()
        composeRule.runOnIdle {
            assertEquals(LocalTime.of(8, 7), chosen)
            assertEquals(0, dismissed)
        }
    }

    @Test
    fun loadingLoadFailureAndSaveFailureUseFinitePoliteStatusAndRetry() {
        setScreen(WidgetConfigurationUiState.Loading)
        assertPoliteStatus()
        composeRule.onNodeWithText("raw exception private path", substring = true)
            .assertDoesNotExist()

        var retries = 0
        setScreen(
            WidgetConfigurationUiState.LoadFailed(),
            actions(onRetry = { retries += 1 })
        )
        assertPoliteStatus()
        reachable("widget_config_retry").performClick()

        setScreen(
            WidgetConfigurationUiState.SaveFailed(
                baseline = draft(),
                draft = draft(opacity = 0),
                dirty = true
            ),
            actions(onRetry = { retries += 1 })
        )
        assertPoliteStatus()
        reachable("widget_config_retry").performClick()
        composeRule.runOnIdle { assertEquals(2, retries) }
    }

    @Test
    fun validationIsFiniteAndSavingDisablesSaveCancelBackFacingActions() {
        setScreen(
            content().copy(
                saving = true,
                validation = WidgetConfigurationError.INVALID_TIME_RANGE
            )
        )
        assertPoliteStatus()
        composeRule.onNodeWithTag("widget_config_save").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("widget_config_cancel").performScrollTo().assertIsNotEnabled()
        listOf(
            "widget_config_start_time",
            "widget_config_end_time",
            "widget_config_opacity",
            "widget_config_font_size",
            "widget_config_theme_system",
            "widget_config_theme_light",
            "widget_config_theme_dark"
        ).forEach { composeRule.onNodeWithTag(it).performScrollTo().assertIsNotEnabled() }
    }

    @Test
    fun dirtyDiscardDialogHasTwoReachableActionsAndExactCallbacks() {
        var discarded = 0
        var kept = 0
        setScreen(
            content().copy(dirty = true),
            actions(
                onConfirmDiscard = { discarded += 1 },
                onKeepEditing = { kept += 1 }
            ),
            showDiscardConfirmation = true
        )

        composeRule.onNodeWithTag("widget_config_discard_dialog").assertIsDisplayed()
        reachableDialogAction("widget_config_discard_confirm").performClick()
        reachableDialogAction("widget_config_discard_keep_editing").performClick()
        composeRule.runOnIdle {
            assertEquals(1, discarded)
            assertEquals(1, kept)
        }
    }

    @Config(qualifiers = "zh-rCN-w360dp-h640dp-420dpi")
    @Test
    fun compactChineseDarkTwentySpAtTwoHundredPercentIsScrollable() {
        assertResponsive(360, ThemeMode.DARK, 20, 2f, expectedTitle = "配置")
    }

    @Config(qualifiers = "en-rUS-w600dp-h800dp-420dpi")
    @Test
    fun mediumEnglishSystemEightSpIsScrollable() {
        assertResponsive(600, ThemeMode.SYSTEM, 8, 1f, expectedTitle = "Widget")
    }

    @Config(qualifiers = "en-rUS-w840dp-h900dp-420dpi")
    @Test
    fun expandedEnglishLightTwentySpAtTwoHundredPercentIsScrollable() {
        assertResponsive(840, ThemeMode.LIGHT, 20, 2f, expectedTitle = "Widget")
    }

    private fun assertResponsive(
        width: Int,
        theme: ThemeMode,
        fontSize: Int,
        fontScale: Float,
        expectedTitle: String
    ) {
        setScreen(
            content(),
            options = RenderOptions(width, theme, fontSize, fontScale)
        )
        composeRule.onNodeWithTag("widget_config_title")
            .assertIsDisplayed()
            .assertTextContains(expectedTitle, substring = true)
        reachable("widget_config_start_time")
        reachable("widget_config_end_time")
        reachable("widget_config_save")
        reachable("widget_config_cancel")
    }

    private fun assertPoliteStatus() {
        val semantics = composeRule.onNodeWithTag("widget_config_status")
            .performScrollTo().assertIsDisplayed().fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, semantics.getOrNull(SemanticsProperties.LiveRegion))
    }

    private fun assertRadio(tag: String, selected: Boolean) {
        val node = composeRule.onNodeWithTag(tag).performScrollTo()
        if (selected) node.assertIsSelected()
        val semantics = node.fetchSemanticsNode().config
        assertEquals(Role.RadioButton, semantics.getOrNull(SemanticsProperties.Role))
    }

    private fun reachable(tag: String) = composeRule.onNodeWithTag(tag)
        .performScrollTo()
        .assertIsDisplayed()
        .assertHasClickAction()
        .assertHeightIsAtLeast(48.dp)

    private fun reachableSlider(tag: String) = composeRule.onNodeWithTag(tag)
        .performScrollTo()
        .assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp)

    private fun reachableDialogAction(tag: String) = composeRule.onNodeWithTag(tag)
        .assertIsDisplayed()
        .assertHasClickAction()
        .assertHeightIsAtLeast(48.dp)

    private fun setScreen(
        state: WidgetConfigurationUiState,
        actions: WidgetConfigurationActions = actions(),
        options: RenderOptions = RenderOptions(),
        showDiscardConfirmation: Boolean = false
    ) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, options.fontScale)
                ) {
                    ClenderTheme(
                        AppearanceUiState(options.theme, options.fontSizeSp, 13)
                    ) {
                        WidgetConfigurationScreen(
                            state = state,
                            actions = actions,
                            showDiscardConfirmation = showDiscardConfirmation,
                            modifier = Modifier.width(options.widthDp.dp)
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private data class RenderOptions(
        val widthDp: Int = 360,
        val theme: ThemeMode = ThemeMode.LIGHT,
        val fontSizeSp: Int = 13,
        val fontScale: Float = 1f
    )

    private companion object {
        fun draft(
            opacity: Int = 100,
            fontSize: Int = 13,
            theme: WidgetThemeMode = WidgetThemeMode.SYSTEM
        ) = WidgetConfigurationDraft(
            appWidgetId = 41,
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(22, 0),
            opacityPercent = opacity,
            fontSizeSp = fontSize,
            theme = theme
        )

        fun content(value: WidgetConfigurationDraft = draft()) = WidgetConfigurationUiState.Content(
            baseline = value,
            draft = value,
            dirty = false,
            saving = false,
            validation = null
        )

        fun actions(
            onRetry: () -> Unit = {},
            onThemeChange: (WidgetThemeMode) -> Unit = {},
            onConfirmDiscard: () -> Unit = {},
            onKeepEditing: () -> Unit = {}
        ) = WidgetConfigurationActions(
            onRetry = onRetry,
            onStartTimeClick = {},
            onEndTimeClick = {},
            onOpacityChange = {},
            onFontSizeChange = {},
            onThemeChange = onThemeChange,
            onSave = {},
            onCancel = {},
            onConfirmDiscard = onConfirmDiscard,
            onKeepEditing = onKeepEditing
        )
    }
}
