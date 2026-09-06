package com.molotov.clender.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.app.ai.AiCoordinatorState
import com.molotov.clender.app.ai.AiSubmissionDecision
import com.molotov.clender.ui.ai.AiSubmissionViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
class QuickAiActivityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val application: QuickAiTestApplication
        get() = RuntimeEnvironment.getApplication() as QuickAiTestApplication

    private var controller: ActivityController<QuickAiActivity>? = null

    @After
    fun releaseActivityObservers() {
        closeActivity()
        assertEquals(0, application.repository.collectors)
        assertEquals(0, application.gateway.state.subscriptionCount.value)
        assertEquals(0, application.appearance.subscriptionCount.value)
    }

    @Test
    fun invalidColdEntryFinishesBeforeAnyRuntimeReadOrContent() {
        launch(quickAiTestIntent(application, 404))

        assertTrue(requireNotNull(controller).get().isFinishing)
        assertEquals(0, application.runtimeReads)
        assertEquals(0, application.activeWrites)
        assertTrue(application.gateway.submissions.isEmpty())
        composeRule.onNodeWithTag("quick_ai_input").assertDoesNotExist()
    }

    @Test
    fun validReadyEntryReusesActiveConversationWithoutAutomaticSubmission() {
        launchOwned()

        composeRule.onNodeWithTag("quick_ai_input").assertIsEnabled()
        composeRule.onNodeWithTag("quick_ai_send").assertIsNotEnabled()
        assertEquals(1, application.modelCreations)
        assertEquals(0, application.repository.createCalls)
        assertEquals(QUICK_AI_TEST_CONVERSATION, application.active.value)
        assertTrue(application.gateway.submissions.isEmpty())
        composeRule.onNodeWithText("private conversation title").assertDoesNotExist()
        composeRule.onNodeWithText(QUICK_AI_TEST_CONVERSATION).assertDoesNotExist()
        composeRule.onNodeWithTag("conversation_messages_list").assertDoesNotExist()
    }

    @Test
    fun systemDeliveredExcludeFromRecentsColdEntryOpensComposerWithoutAutomaticSubmission() {
        bindQuickAiTestWidget(application)
        val delivered = quickAiTestIntent(application)
            .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        assertEquals(0x24800000, delivered.flags)

        launch(delivered)

        val activity = requireNotNull(controller).get()
        assertEquals(0x24800000, activity.intent.flags)
        assertFalse(activity.isFinishing)
        composeRule.onNodeWithTag("quick_ai_input").assertIsEnabled()
        composeRule.onNodeWithTag("quick_ai_send").assertIsNotEnabled()
        assertEquals(1, application.modelCreations)
        assertEquals(0, application.repository.createCalls)
        assertEquals(QUICK_AI_TEST_CONVERSATION, application.active.value)
        assertTrue(application.gateway.submissions.isEmpty())
    }

    @Test
    fun systemNewTaskColdEntryOpensReadyComposer() {
        assertSystemTaskColdEntry(0x34000000)
    }

    @Test
    fun systemNewTaskAndExcludeColdEntryOpensReadyComposer() {
        assertSystemTaskColdEntry(0x34800000)
    }

    @Test
    fun systemNewTaskAndBroughtToFrontColdEntryOpensReadyComposer() {
        assertSystemTaskColdEntry(0x34400000)
    }

    @Test
    fun actualSystemNewTaskExcludeAndBroughtToFrontColdEntryOpensReadyComposer() {
        assertSystemTaskColdEntry(0x34c00000)
    }

    @Test
    fun systemNewTaskHotEntriesKeepDraftAndReplaceOnlyWidgetIdentity() {
        launchOwned()
        composeRule.onNodeWithTag("quick_ai_input").performTextInput(QUICK_AI_TEST_DRAFT)
        val model = submission()
        listOf(0x34000000, 0x34800000, 0x34400000, 0x34c00000).forEachIndexed { index, flags ->
            val widgetId = 72 + index
            bindQuickAiTestWidget(application, widgetId)
            val delivered = quickAiTestIntent(application, widgetId).apply { this.flags = flags }
            requireNotNull(controller).newIntent(delivered)
            composeRule.waitForIdle()

            val activity = requireNotNull(controller).get()
            assertFalse(activity.isFinishing)
            assertEquals(flags, activity.intent.flags)
            assertEquals(
                widgetId,
                activity.intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            )
            assertSame(model, submission())
            assertEquals(QUICK_AI_TEST_DRAFT, submission().state.value.draft)
            composeRule.onNodeWithTag("quick_ai_input").assertIsEnabled()
            assertEquals(1, application.modelCreations)
            assertEquals(0, application.repository.createCalls)
            assertTrue(application.gateway.submissions.isEmpty())
        }
    }

    @Test
    fun systemDeliveredExcludeFromRecentsWithOtherFlagsStillRejectsColdEntryBeforeRuntime() {
        bindQuickAiTestWidget(application)
        val delivered = quickAiTestIntent(application).addFlags(
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        launch(delivered)

        assertTrue(requireNotNull(controller).get().isFinishing)
        assertEquals(0, application.runtimeReads)
        assertEquals(0, application.modelCreations)
        assertEquals(0, application.activeWrites)
        assertTrue(application.gateway.submissions.isEmpty())
        composeRule.onNodeWithTag("quick_ai_input").assertDoesNotExist()
    }

    @Test
    fun loadingCannotSubmitAndExplicitReadinessEnablesComposer() {
        val gate = CompletableDeferred<Unit>()
        application.repository.readGate = gate
        launchOwned()

        composeRule.onNodeWithTag("quick_ai_activity_loading").assertIsDisplayed()
        assertTrue(application.gateway.submissions.isEmpty())
        composeRule.runOnIdle { gate.complete(Unit) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quick_ai_input").assertIsEnabled()
        assertTrue(application.gateway.submissions.isEmpty())
    }

    @Test
    fun loadFailureOffersFiniteRetryWithoutPrivateException() {
        application.repository.failReads = true
        launchOwned()

        composeRule.onNodeWithTag("quick_ai_activity_error").assertIsDisplayed()
        composeRule.onNodeWithText("private repository failure", substring = true)
            .assertDoesNotExist()
        assertTrue(application.gateway.submissions.isEmpty())
        composeRule.runOnIdle { application.repository.failReads = false }
        composeRule.onNodeWithTag("quick_ai_activity_retry").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quick_ai_input").assertIsEnabled()
    }

    @Test
    fun emptyRepositoryCreatesOnlyOneLocalizedConversationAcrossHotEntry() {
        application.repository.conversations.clear()
        application.active.value = null
        launchOwned()
        requireNotNull(controller).newIntent(quickAiTestIntent(application))
        composeRule.waitForIdle()

        assertEquals(1, application.repository.createCalls)
        assertEquals(1, application.modelCreations)
        val created = application.repository.conversations.values.single()
        assertTrue(created.id.matches(Regex("[0-9a-f]{32}")))
        assertEquals(
            application.getString(com.molotov.clender.R.string.conversation_default_title),
            created.title
        )
        assertTrue(application.gateway.submissions.isEmpty())
    }

    @Test
    fun acceptedSendTrimsOnlyOutsideClearsDraftAndBlocksDuplicate() {
        launchOwned()
        composeRule.onNodeWithTag("quick_ai_input").performTextInput(QUICK_AI_TEST_DRAFT)
        composeRule.onNodeWithTag("quick_ai_send").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(
            listOf(QUICK_AI_TEST_CONVERSATION to QUICK_AI_TEST_DRAFT.trim()),
            application.gateway.submissions
        )
        assertEquals("", submission().state.value.draft)
        composeRule.onNodeWithTag("quick_ai_send").assertIsNotEnabled()
        composeRule.onNodeWithTag("quick_ai_working").assertIsDisplayed()
    }

    @Test
    fun unconfiguredSendPreservesDraftAndOffersSettings() {
        application.gateway.decision = AiSubmissionDecision.UNCONFIGURED
        launchOwned()
        composeRule.onNodeWithTag("quick_ai_input").performTextInput(QUICK_AI_TEST_DRAFT)
        composeRule.onNodeWithTag("quick_ai_send").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(QUICK_AI_TEST_DRAFT, submission().state.value.draft)
        composeRule.onNodeWithTag("quick_ai_unconfigured").assertIsDisplayed()
        composeRule.onNodeWithTag("quick_ai_open_settings").assertIsEnabled()
        assertTrue(application.repository.messages.value.isEmpty())
    }

    @Test
    fun invalidHotEntryPreservesIntentDraftStateAndRuntimeReads() {
        launchOwned()
        composeRule.onNodeWithTag("quick_ai_input").performTextInput(QUICK_AI_TEST_DRAFT)
        val activity = requireNotNull(controller).get()
        val acceptedIntent = activity.intent
        val before = submission().state.value
        val reads = application.runtimeReads
        val invalid = quickAiTestIntent(application).putExtra("prompt", "untrusted")

        requireNotNull(controller).newIntent(invalid)
        composeRule.waitForIdle()

        assertSame(acceptedIntent, activity.intent)
        assertEquals(before, submission().state.value)
        assertEquals(reads, application.runtimeReads)
        assertEquals(1, application.modelCreations)
        assertTrue(application.gateway.submissions.isEmpty())
    }

    @Test
    fun validHotEntryChangesOnlyWidgetIdentityAndKeepsDraft() {
        launchOwned()
        composeRule.onNodeWithTag("quick_ai_input").performTextInput(QUICK_AI_TEST_DRAFT)
        bindQuickAiTestWidget(application, 72)
        requireNotNull(controller).newIntent(quickAiTestIntent(application, 72))
        composeRule.waitForIdle()

        assertEquals(QUICK_AI_TEST_DRAFT, submission().state.value.draft)
        assertEquals(1, application.modelCreations)
        assertTrue(application.gateway.submissions.isEmpty())
        assertEquals(
            72,
            requireNotNull(controller).get().intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                -1
            )
        )
    }

    @Test
    fun recreateRetainsViewModelDraftAndDoesNotReactivateConversation() {
        launchOwned()
        composeRule.onNodeWithTag("quick_ai_input").performTextInput(QUICK_AI_TEST_DRAFT)
        val before = submission()
        requireNotNull(controller).recreate()
        composeRule.waitForIdle()

        assertSame(before, submission())
        assertEquals(QUICK_AI_TEST_DRAFT, submission().state.value.draft)
        composeRule.onNodeWithTag("quick_ai_input").assertTextContains(QUICK_AI_TEST_DRAFT)
        assertEquals(1, application.modelCreations)
        assertEquals(1, application.repository.collectors)
        assertTrue(application.gateway.submissions.isEmpty())
    }

    @Test
    fun finishReopenDropsDraftAndReleasesObserversWithoutCancellingSharedRequest() {
        launchOwned()
        composeRule.onNodeWithTag("quick_ai_input").performTextInput(QUICK_AI_TEST_DRAFT)
        val working = AiCoordinatorState.Working(QUICK_AI_TEST_CONVERSATION)
        application.gateway.state.value = working
        requireNotNull(controller).get().finish()
        closeActivity()

        assertEquals(working, application.gateway.state.value)
        assertEquals(0, application.repository.collectors)
        assertEquals(0, application.gateway.state.subscriptionCount.value)
        application.gateway.state.value = AiCoordinatorState.Idle
        launchOwned()
        assertEquals("", submission().state.value.draft)
        assertFalse(requireNotNull(controller).get().isFinishing)
    }

    private fun launchOwned() {
        bindQuickAiTestWidget(application)
        launch(quickAiTestIntent(application))
    }

    private fun assertSystemTaskColdEntry(flags: Int) {
        bindQuickAiTestWidget(application)
        launch(quickAiTestIntent(application).apply { this.flags = flags })

        val activity = requireNotNull(controller).get()
        assertFalse(activity.isFinishing)
        assertEquals(flags, activity.intent.flags)
        composeRule.onNodeWithTag("quick_ai_input").assertIsEnabled()
        composeRule.onNodeWithTag("quick_ai_send").assertIsNotEnabled()
        assertEquals(1, application.modelCreations)
        assertEquals(0, application.repository.createCalls)
        assertEquals(QUICK_AI_TEST_CONVERSATION, application.active.value)
        assertTrue(application.gateway.submissions.isEmpty())
    }

    private fun launch(intent: Intent) {
        controller = Robolectric.buildActivity(QuickAiActivity::class.java, intent)
            .create().start().resume().visible()
        composeRule.waitForIdle()
    }

    private fun submission(): AiSubmissionViewModel =
        ViewModelProvider(requireNotNull(controller).get())[AiSubmissionViewModel::class.java]

    private fun closeActivity() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
}
