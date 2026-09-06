package com.molotov.clender.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class C3SettingsAdaptiveAccessibilityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val composeHost = com.molotov.clender.testsupport.RobolectricComposeHost()

    @Before
    fun startHost() = composeHost.start()

    @After
    fun closeHost() = composeHost.close()

    @Test
    fun threeSectionsRemainSelectableAndActionsReachableAtEveryBoundary() {
        val widths = listOf(360, 599, 600, 839, 840)
        val themes = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)
        val directions = listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)
        widths.forEachIndexed { index, width ->
            val options = RenderOptions(
                widthDp = width,
                direction = directions[index % directions.size],
                theme = themes[index % themes.size],
                appFontSizeSp = if (index % 2 == 0) 20 else 8,
                fontScale = if (index % 2 == 0) 2f else 1f
            )
            SettingsSection.entries.forEach { section ->
                setScreen(readyState().copy(section = section), options)
                composeRule.onNodeWithTag("settings_section_${section.name.lowercase()}")
                    .assertIsSelected()
                    .assertHeightIsAtLeast(48.dp)
                listOf(
                    "settings_section_application",
                    "settings_section_ai",
                    "settings_section_webdav"
                ).forEach { tag ->
                    composeRule.onNodeWithTag(
                        tag
                    ).assertHasClickAction().assertHeightIsAtLeast(48.dp)
                }
                when (section) {
                    SettingsSection.APPLICATION -> listOf(
                        "settings_theme_system",
                        "settings_theme_light",
                        "settings_theme_dark",
                        "settings_save_application"
                    )

                    SettingsSection.AI -> listOf(
                        "settings_ai_thinking",
                        "settings_fetch_models",
                        "settings_save_ai"
                    )

                    SettingsSection.WEBDAV -> listOf(
                        "settings_webdav_enabled",
                        "settings_webdav_save",
                        "settings_webdav_test_connection",
                        "settings_webdav_sync_now"
                    )
                }.forEach { tag ->
                    composeRule.onNodeWithTag(tag)
                        .performScrollTo()
                        .assertIsDisplayed()
                        .assertHeightIsAtLeast(48.dp)
                }
            }
        }
    }

    @Test
    fun thinkingAndWebDavEnabledExposeExactlyOneToggleActionAndSwitchRole() {
        setScreen(readyState().copy(section = SettingsSection.AI))
        assertSingleSwitchSemantics("settings_ai_thinking")

        setScreen(readyState().copy(section = SettingsSection.WEBDAV))
        assertSingleSwitchSemantics("settings_webdav_enabled")
    }

    @Test
    fun apiKeyAndWebDavPasswordNeverLeakIntoMergedOrUnmergedSemantics() {
        val apiKey = "api-key-c3-secret"
        setScreen(
            readyState().copy(section = SettingsSection.AI),
            actions = actions()
        )
        composeRule.onNodeWithTag("settings_ai_key").performTextInput(apiKey)
        assertSecretFieldRedacted("settings_ai_key", apiKey)

        val password = "webdav-password-c3-secret"
        setScreen(
            readyState().copy(section = SettingsSection.WEBDAV),
            actions = actions()
        )
        composeRule.onNodeWithTag("settings_webdav_password").performTextInput(password)
        assertSecretFieldRedacted("settings_webdav_password", password)
    }

    @Test
    fun finiteAiAndWebDavStatesUsePoliteLiveRegionsWithoutUrlOrExceptionText() {
        val raw = "https://private.example/endpoint?token=raw-exception-body"
        setScreen(
            readyState().copy(section = SettingsSection.AI, status = SettingsStatus.PROVIDER)
        )
        val aiError = composeRule.onNodeWithTag("settings_models_error").fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, aiError.getOrNull(SemanticsProperties.LiveRegion))
        assertFalse(aiError.toString().contains(raw))

        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                webDavSyncStatus = WebDavSyncStatusUi.Failed(WebDavSyncFailureCodeUi.TRANSPORT)
            )
        )
        val webDavError = composeRule.onNodeWithTag("settings_webdav_sync_status")
            .fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, webDavError.getOrNull(SemanticsProperties.LiveRegion))
        assertFalse(webDavError.toString().contains(raw))
    }

    @Test
    fun dirtyDiscardDialogKeepsBothReachableActionsAndDoesNotExposeSensitiveState() {
        var confirmed = 0
        var cancelled = 0
        setScreen(
            readyState().copy(
                dirty = true,
                discardConfirmation = true,
                secretInput = SecretInput.from("hidden-key".toCharArray()),
                webDavSecretInput = SecretInput.from("hidden-password".toCharArray())
            ),
            actions = actions(
                onConfirmDiscard = { confirmed += 1 },
                onCancelDiscard = { cancelled += 1 }
            )
        )
        composeRule.onNodeWithTag("settings_discard_cancel")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithTag("settings_discard_confirm")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, cancelled)
            assertEquals(1, confirmed)
        }
        composeRule.onNodeWithText("hidden-key").assertDoesNotExist()
        composeRule.onNodeWithText("hidden-password").assertDoesNotExist()
    }

    @Test
    fun settingsScrollPositionIsRestoredPerSectionInsteadOfSharedAcrossTabs() {
        var state by mutableStateOf(
            readyState().copy(
                section = SettingsSection.AI,
                models = (0..20).map { "model-$it" }
            )
        )
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                SettingsScreen(
                    state = state,
                    actions = actions(onSectionChange = { state = state.copy(section = it) }),
                    modifier = Modifier.width(360.dp)
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_content").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_save_ai").performScrollTo()
        val aiBottom = composeRule.onNodeWithTag("settings_save_ai")
            .fetchSemanticsNode().boundsInRoot.top

        composeRule.runOnUiThread { state = state.copy(section = SettingsSection.APPLICATION) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_save_application").performScrollTo()
        composeRule.runOnIdle { assertTrue(state.section == SettingsSection.APPLICATION) }

        composeRule.runOnUiThread { state = state.copy(section = SettingsSection.AI) }
        composeRule.waitForIdle()
        val restoredAiBottom = composeRule.onNodeWithTag("settings_save_ai")
            .fetchSemanticsNode().boundsInRoot.top
        assertEquals(aiBottom, restoredAiBottom, 1f)
        assertNotEquals("The tab scroll positions must not share one offset", 0f, aiBottom)
    }

    private fun assertSingleSwitchSemantics(tag: String) {
        val target = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .fetchSemanticsNode()
        val actionNodes = interactiveNodes(target)
        assertEquals("$tag must expose one logical action", 1, actionNodes.size)
        assertEquals(Role.Switch, actionNodes.single().config.getOrNull(SemanticsProperties.Role))
        assertTrue(actionNodes.single().config.contains(SemanticsActions.OnClick))
    }

    private fun assertSecretFieldRedacted(tag: String, sentinel: String) {
        listOf(false, true).forEach { unmerged ->
            val config = composeRule.onNodeWithTag(tag, useUnmergedTree = unmerged)
                .fetchSemanticsNode().config
            assertFalse(
                "$tag leaked in semantics tree=$unmerged",
                config.toString().contains(sentinel)
            )
        }
        composeRule.onNodeWithText(sentinel, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(sentinel).assertDoesNotExist()
    }

    private fun interactiveNodes(node: SemanticsNode): List<SemanticsNode> {
        val own = if (
            node.config.contains(SemanticsActions.OnClick) ||
            node.config.contains(SemanticsProperties.ToggleableState)
        ) {
            listOf(node)
        } else {
            emptyList()
        }
        return own + node.children.flatMap(::interactiveNodes)
    }

    private fun setScreen(
        state: SettingsUiState,
        options: RenderOptions = RenderOptions(),
        actions: SettingsActions = actions()
    ) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                CompositionLocalProvider(
                    LocalLayoutDirection provides options.direction,
                    LocalDensity provides Density(LocalDensity.current.density, options.fontScale)
                ) {
                    ClenderTheme(
                        AppearanceUiState(
                            options.theme,
                            options.appFontSizeSp,
                            state.widgetFontSizeSp
                        )
                    ) {
                        SettingsScreen(
                            state = state,
                            actions = actions,
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
        val direction: LayoutDirection = LayoutDirection.Ltr,
        val theme: ThemeMode = ThemeMode.SYSTEM,
        val appFontSizeSp: Int = 13,
        val fontScale: Float = 1f
    )

    private fun readyState() = SettingsUiState(
        active = true,
        status = SettingsStatus.READY,
        apiKeyConfigured = true,
        webDavPasswordConfigured = true
    )

    private fun actions(
        onSectionChange: (SettingsSection) -> Unit = {},
        onConfirmDiscard: () -> Unit = {},
        onCancelDiscard: () -> Unit = {}
    ) = SettingsActions(
        onSectionChange = onSectionChange,
        onAppearanceChange = {},
        onAiChange = {},
        onSecretInput = {},
        onSaveAppearance = {},
        onSaveAi = {},
        onFetchModels = {},
        onRequestRemoveKey = {},
        onConfirmRemoveKey = {},
        onCancelRemoveKey = {},
        onConfirmDiscard = onConfirmDiscard,
        onCancelDiscard = onCancelDiscard
    )
}
