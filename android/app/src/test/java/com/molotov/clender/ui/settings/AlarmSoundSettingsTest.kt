package com.molotov.clender.ui.settings

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.molotov.clender.data.settings.AlarmSoundStore
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [26, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlarmSoundSettingsTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val temporary = TemporaryFolder()
    private val host = RobolectricComposeHost()

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun lightSmallSettingsExposeAudioSelection() = show(ThemeMode.LIGHT, 8)

    @Test
    fun darkLargeSettingsExposeAudioSelection() = show(ThemeMode.DARK, 20)

    @Test
    fun audioPickerShowsBusyAndFailureAndLeavingCancelsSelection() = runTest {
        val controller = AlarmSoundController(
            AlarmSoundStore(temporary.newFolder()) { },
            this,
            StandardTestDispatcher(testScheduler)
        )
        host.activity.setContent {
            ClenderTheme(AppearanceUiState(ThemeMode.LIGHT, 13, 13)) {
                AlarmSoundSettingsContent(controller)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("alarm_sound_choose").assertIsNotEnabled()
        composeRule.onNodeWithTag("alarm_sound_remove").assertIsNotEnabled()
        advanceUntilIdle()
        composeRule.runOnIdle {
            controller.launchSelection { _, _ -> throw SecurityException("synthetic") }
        }
        composeRule.onNodeWithTag("alarm_sound_error").assertIsDisplayed()
        composeRule.onNodeWithTag("alarm_sound_choose").performClick()
        val intent = Shadows.shadowOf(host.activity).nextStartedActivity
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(
            requireNotNull(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
                .contentEquals(arrayOf("audio/*"))
        )
        composeRule.onNodeWithTag("alarm_sound_busy").assertIsDisplayed()
        composeRule.onNodeWithTag("alarm_sound_choose").assertIsNotEnabled()
        host.activity.setContent { }
        composeRule.waitForIdle()
        assertFalse(controller.busy)
        advanceUntilIdle()
    }

    private fun show(theme: ThemeMode, size: Int) {
        host.activity.setContent {
            ClenderTheme(AppearanceUiState(theme, size, 13)) {
                SettingsScreen(
                    SettingsUiState(active = true, status = SettingsStatus.READY),
                    SettingsActions(
                        onSectionChange = {}, onAppearanceChange = {}, onAiChange = {},
                        onSecretInput = {}, onSaveAppearance = {}, onSaveAi = {},
                        onFetchModels = {}, onRequestRemoveKey = {}, onConfirmRemoveKey = {},
                        onCancelRemoveKey = {}, onConfirmDiscard = {}, onCancelDiscard = {}
                    )
                )
            }
        }
        composeRule.onNodeWithTag("alarm_sound_choose").performScrollTo()
            .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
    }
}
