package com.molotov.clender.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
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
@Config(sdk = [26, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T76TransparentSettingsTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val host = RobolectricComposeHost()

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun everySettingsGroupAndTabPreservesBackgroundPixels() {
        val section = mutableStateOf(SettingsSection.APPLICATION)
        val theme = mutableStateOf(ThemeMode.LIGHT)
        host.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ClenderTheme(AppearanceUiState(theme.value, 13, 13)) {
                    Box(Modifier.size(360.dp, 640.dp).background(BACKDROP)) {
                        SettingsScreen(
                            SettingsUiState(
                                active = true,
                                status = SettingsStatus.READY,
                                section = section.value
                            ),
                            actions { section.value = it }
                        )
                    }
                }
            }
        }
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            composeRule.runOnIdle { theme.value = mode }
            GROUPS.forEach { (page, groups) ->
                composeRule.runOnIdle { section.value = page }
                composeRule.waitForIdle()
                assertBackground("settings_section_${page.name.lowercase()}", 6)
                groups.forEach { group ->
                    composeRule.onNodeWithTag("settings_group_$group").performScrollTo()
                    assertBackground("settings_group_$group", 4)
                }
            }
        }
    }

    @Test
    fun transparentGroupTextUsesActualThemeForegroundAfterThemeSwitch() {
        val theme = mutableStateOf(ThemeMode.LIGHT)
        var expected = Color.Unspecified
        host.activity.setContent {
            ClenderTheme(AppearanceUiState(theme.value, 13, 13)) {
                expected = MaterialTheme.colorScheme.onBackground
                SettingsGroupCard("probe") { Text("Foreground probe") }
            }
        }
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.LIGHT)) {
            composeRule.runOnIdle { theme.value = mode }
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText("Foreground probe")
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                    assertTrue(it(layouts))
                }
            assertEquals(expected, layouts.single().layoutInput.style.color)
        }
    }

    @Test
    fun narrowLargeTextTabsKeepSelectionAndTouchTargets() {
        val section = mutableStateOf(SettingsSection.APPLICATION)
        host.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ClenderTheme(AppearanceUiState(ThemeMode.DARK, 20, 20)) {
                    Box(Modifier.size(320.dp, 640.dp)) {
                        SettingsScreen(
                            SettingsUiState(
                                active = true,
                                status = SettingsStatus.READY,
                                section = section.value
                            ),
                            actions { section.value = it }
                        )
                    }
                }
            }
        }
        SettingsSection.entries.forEach { page ->
            composeRule.onNodeWithTag("settings_section_${page.name.lowercase()}")
                .assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
            composeRule.runOnIdle { assertEquals(page, section.value) }
        }
    }

    private fun assertBackground(tag: String, inset: Int) {
        val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val actual = composeRule.runOnUiThread {
            val view = host.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                bitmap.getPixel(bounds.center.x.toInt(), bounds.top.toInt() + inset)
            } finally {
                bitmap.recycle()
            }
        }
        assertEquals("Background must show through $tag", BACKDROP.toArgb(), actual)
    }

    private companion object {
        val BACKDROP = Color(0xFF41A383)
        val GROUPS = mapOf(
            SettingsSection.APPLICATION to listOf(
                "appearance",
                "background",
                "alarm_sound",
                "alert_permissions"
            ),
            SettingsSection.AI to listOf("ai_connection", "ai_generation", "ai_prompt"),
            SettingsSection.WEBDAV to listOf("webdav_connection", "webdav_operations")
        )

        fun actions(onSection: (SettingsSection) -> Unit) = SettingsActions(
            onSectionChange = onSection,
            onAppearanceChange = {},
            onAiChange = {},
            onSecretInput = {},
            onSaveAppearance = {},
            onSaveAi = {},
            onFetchModels = {},
            onRequestRemoveKey = {},
            onConfirmRemoveKey = {},
            onCancelRemoveKey = {},
            onConfirmDiscard = {},
            onCancelDiscard = {}
        )
    }
}
