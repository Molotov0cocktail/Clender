package com.molotov.clender.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.molotov.clender.app.MainActivity
import com.molotov.clender.app.ai.AiSubmissionDecision
import com.molotov.clender.ui.foundation.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = QuickAiTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickAiActivityNavigationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val application: QuickAiTestApplication
        get() = RuntimeEnvironment.getApplication() as QuickAiTestApplication

    private var controller: ActivityController<QuickAiActivity>? = null

    @After
    fun releaseActivity() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, application.repository.collectors)
        assertEquals(0, application.gateway.state.subscriptionCount.value)
    }

    @Test
    fun openConversationRebuildsExactInternalIntentWithoutDraftOrUnknownHotExtras() {
        launch()
        composeRule.onNodeWithTag("quick_ai_input").performTextInput(QUICK_AI_TEST_DRAFT)
        requireNotNull(controller).newIntent(
            quickAiTestIntent(application).putExtra("forwarded_payload", "must not forward")
        )
        composeRule.onNodeWithTag("quick_ai_open_conversation")
            .performScrollTo().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .performClick()

        assertNavigation(71, WidgetQuickAiDestination.CONVERSATION)
        assertTrue(application.gateway.submissions.isEmpty())
    }

    @Test
    fun unconfiguredSettingsUsesSeparateExactDestinationWithoutPrompt() {
        application.gateway.decision = AiSubmissionDecision.UNCONFIGURED
        launch()
        composeRule.onNodeWithTag("quick_ai_input").performTextInput(QUICK_AI_TEST_DRAFT)
        composeRule.onNodeWithTag("quick_ai_send").performScrollTo().performClick()
        composeRule.onNodeWithTag("quick_ai_open_settings")
            .performScrollTo().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .performClick()

        assertNavigation(71, WidgetQuickAiDestination.SETTINGS)
    }

    @Test
    fun validHotWidgetIdentityIsUsedForSubsequentNavigation() {
        launch()
        bindQuickAiTestWidget(application, 72)
        requireNotNull(controller).newIntent(quickAiTestIntent(application, 72))
        composeRule.onNodeWithTag("quick_ai_open_conversation").performScrollTo().performClick()

        assertNavigation(72, WidgetQuickAiDestination.CONVERSATION)
        assertTrue(application.gateway.submissions.isEmpty())
    }

    @Test
    fun backFinishesWithoutStartingMainActivityOrSubmitting() {
        launch()
        composeRule.onNodeWithTag("quick_ai_input").performTextInput(QUICK_AI_TEST_DRAFT)
        composeRule.onNodeWithTag("quick_ai_back").performScrollTo().performClick()

        assertTrue(requireNotNull(controller).get().isFinishing)
        assertNull(Shadows.shadowOf(requireNotNull(controller).get()).nextStartedActivity)
        assertTrue(application.gateway.submissions.isEmpty())
    }

    @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-420dpi")
    @Test
    fun landscapeDoubleFontScaleKeepsHeadingComposerAndNavigationScrollable() {
        RuntimeEnvironment.setFontScale(2f)
        application.appearance.value = application.appearance.value.copy(
            themeMode = ThemeMode.DARK,
            appFontSizeSp = 20
        )
        launch()
        composeRule.onNodeWithTag("quick_ai_input").performScrollTo()
            .performTextInput("长输入 🌏\n".repeat(12))
        listOf(
            "quick_ai_send",
            "quick_ai_open_conversation",
            "quick_ai_activity_open_settings",
            "quick_ai_back"
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        composeRule.runOnIdle {
            application.appearance.value = application.appearance.value.copy(
                themeMode = ThemeMode.LIGHT,
                appFontSizeSp = 8
            )
        }
        composeRule.onNodeWithTag("quick_ai_activity_heading").performScrollTo()
            .assertIsDisplayed()
        assertTrue(application.gateway.submissions.isEmpty())
    }

    @Test
    fun savedStateDoesNotContainDraftOrActiveConversationIdentity() {
        launch()
        composeRule.onNodeWithTag("quick_ai_input").performTextInput(QUICK_AI_TEST_DRAFT)
        val state = Bundle()
        requireNotNull(controller).saveInstanceState(state)

        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(state)
            val bytes = parcel.marshall()
            listOf(Charsets.UTF_8, Charsets.UTF_16LE).forEach { charset ->
                val persisted = bytes.toString(charset)
                assertFalse(persisted.contains(QUICK_AI_TEST_DRAFT.trim()))
                assertFalse(persisted.contains(QUICK_AI_TEST_CONVERSATION))
            }
        } finally {
            parcel.recycle()
        }
        val intent = requireNotNull(controller).get().intent
        assertEquals(setOf(AppWidgetManager.EXTRA_APPWIDGET_ID), intent.extras?.keySet())
    }

    private fun launch() {
        bindQuickAiTestWidget(application)
        controller = Robolectric.buildActivity(
            QuickAiActivity::class.java,
            quickAiTestIntent(application)
        ).create().start().resume().visible()
        composeRule.waitForIdle()
    }

    private fun assertNavigation(widgetId: Int, destination: WidgetQuickAiDestination) {
        val actual = requireNotNull(
            Shadows.shadowOf(requireNotNull(controller).get()).nextStartedActivity
        )
        val expected = WidgetActionIntentContract.quickAiNavigationIntent(
            application,
            widgetId,
            destination
        )
        assertEquals(ComponentName(application, MainActivity::class.java), actual.component)
        assertEquals(application.packageName, actual.`package`)
        assertEquals(expected.action, actual.action)
        assertEquals(expected.data, actual.data)
        assertEquals(expected.flags, actual.flags)
        assertEquals(setOf(AppWidgetManager.EXTRA_APPWIDGET_ID), actual.extras?.keySet())
        assertEquals(widgetId, actual.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertNull(actual.clipData)
        assertNull(actual.selector)
        assertNull(actual.categories)
    }
}
