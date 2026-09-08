package com.molotov.clender.ui.event

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertThrows
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
class EventAlertEditorTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val host = RobolectricComposeHost()

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun editorExposesSeparateNotificationAlarmAndTimerControls() {
        composeRule.runOnUiThread {
            host.activity.setContent {
                ClenderTheme(AppearanceUiState(ThemeMode.LIGHT, 13, 13)) {
                    EventEditorScreen(
                        EventCrudUiState(form = EventFormState.new(LocalDate.of(2026, 9, 9))),
                        {},
                        {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
        listOf(
            "event_alert_notification",
            "event_alert_alarm",
            "event_alert_timer"
        ).forEach { tag ->
            composeRule.onNodeWithTag("event_editor_scroll").performScrollToNode(hasTestTag(tag))
            composeRule.onNodeWithTag(tag).assertHasClickAction()
        }
        listOf(
            "event_alert_notifications_settings",
            "event_alert_background_settings",
            "event_alert_app_settings"
        ).forEach { tag ->
            assertThrows(AssertionError::class.java) {
                composeRule.onNodeWithTag("event_editor_scroll")
                    .performScrollToNode(hasTestTag(tag))
            }
        }
    }
}
