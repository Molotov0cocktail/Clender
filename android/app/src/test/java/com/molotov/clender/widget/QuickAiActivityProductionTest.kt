package com.molotov.clender.widget

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.app.AppContainer
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.testsupport.ProductionActivityTestResources
import com.molotov.clender.ui.ai.ConversationLoadStatus
import com.molotov.clender.ui.ai.ConversationViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickAiActivityProductionTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val application: ClenderApplication
        get() = RuntimeEnvironment.getApplication() as ClenderApplication

    private var controller: ActivityController<QuickAiActivity>? = null

    @After
    fun closeProductionActivityBeforeContainerAndSandbox() {
        ProductionActivityTestResources.close(application) {
            controller?.pause()?.stop()?.destroy()
            controller = null
        }
        assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
        assertFalse(application.filesDir.resolve("datastore").exists())
    }

    @Test
    fun invalidColdEntryDoesNotInitializeContainerDatabasePreferencesOrCompose() {
        launch(quickAiTestIntent(application, 404))

        assertTrue(requireNotNull(controller).get().isFinishing)
        assertFalse(containerInitialized())
        assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
        assertFalse(application.filesDir.resolve(AppContainer.PREFERENCES_RELATIVE_PATH).exists())
        composeRule.onNodeWithTag("quick_ai_input").assertDoesNotExist()
    }

    @Test
    fun unconfiguredProductionSubmissionWritesNoMessagesEventsOrMutationAndReusesActive() {
        bindQuickAiTestWidget(application)
        launch(quickAiTestIntent(application))
        val conversations = ViewModelProvider(requireNotNull(controller).get())[
            ConversationViewModel::class.java
        ]
        composeRule.waitUntil(5_000) {
            // Real DataStore/Room IO resumes the ViewModel on Robolectric's main looper.
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            conversations.state.value.loadStatus in setOf(
                ConversationLoadStatus.READY,
                ConversationLoadStatus.ERROR
            )
        }
        assertEquals(
            "Conversation initialization failed: ${conversations.state.value.errorCode}",
            ConversationLoadStatus.READY,
            conversations.state.value.loadStatus
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quick_ai_input").assertIsEnabled()
            .performTextInput(QUICK_AI_TEST_DRAFT)
        val container = application.container
        val activeBefore =
            runBlocking { container.activeConversationStore.activeConversationId.first() }
        composeRule.onNodeWithTag("quick_ai_send").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithTag("quick_ai_unconfigured")
                .fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking {
            assertEquals(
                activeBefore,
                container.activeConversationStore.activeConversationId.first()
            )
            assertEquals(1, container.conversationRepository.listConversations().size)
            assertTrue(
                container.conversationRepository.observeMessages(requireNotNull(activeBefore))
                    .first().isEmpty()
            )
            assertEquals(0L, container.scheduleMutationVersion.value)
            assertEquals(null, container.eventRepository.findById(1))
        }
    }

    private fun launch(intent: Intent) {
        controller = Robolectric.buildActivity(QuickAiActivity::class.java, intent)
            .create().start().resume().visible()
        composeRule.waitForIdle()
    }

    private fun containerInitialized(): Boolean {
        val field = ClenderApplication::class.java.getDeclaredField("container\$delegate")
        field.isAccessible = true
        return (field.get(application) as Lazy<*>).isInitialized()
    }
}
