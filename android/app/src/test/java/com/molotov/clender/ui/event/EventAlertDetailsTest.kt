package com.molotov.clender.ui.event

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.LocalDateTime
import org.junit.After
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
class EventAlertDetailsTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val host = RobolectricComposeHost()

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun timerAndBackgroundSettingsRemainReachableInEventDetails() {
        val event = eventFixture(startTime = LocalDateTime.now().withSecond(0).withNano(0))
            .copy(notificationEnabled = true, timerMinutes = 25)
        composeRule.runOnUiThread {
            host.activity.setContent {
                ClenderTheme(AppearanceUiState(ThemeMode.DARK, 20, 13)) {
                    EventDetailScreen(
                        EventCrudUiState(event = event, loadStatus = EventCrudLoadStatus.CONTENT),
                        {},
                        {},
                        {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("event_detail_content")
            .performScrollToNode(hasTestTag("event_alert_timer_status"))
        composeRule.onNodeWithTag("event_alert_timer_status").assertIsDisplayed()
        listOf("event_alert_background_settings", "event_alert_app_settings").forEach { tag ->
            composeRule.onNodeWithTag("event_detail_content").performScrollToNode(hasTestTag(tag))
            composeRule.onNodeWithTag(tag).assertHasClickAction()
        }
    }
}
