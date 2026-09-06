package com.molotov.clender.app

import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.testsupport.ProductionActivityTestResources
import com.molotov.clender.ui.ai.ConversationLoadStatus
import com.molotov.clender.ui.ai.ConversationViewModel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityConversationIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun destroyActivityAndCloseProductionContainer() {
        val application = application()
        ProductionActivityTestResources.close(
            application,
            composeRule.activityRule.scenario
        )
    }

    @Test
    fun calendarColdStartStaysLazyAndFirstAiVisitCreatesAndPersistsOneConversation() {
        val viewModel = ViewModelProvider(composeRule.activity)[ConversationViewModel::class.java]

        assertEquals(ConversationLoadStatus.INACTIVE, viewModel.state.value.loadStatus)
        assertTrue(viewModel.state.value.conversations.isEmpty())
        assertTrue(viewModel.state.value.messages.isEmpty())
        assertFalse(dataStoreFile().exists())
        assertTrue(
            runBlocking {
                application().container.conversationRepository.listConversations().isEmpty()
            }
        )

        composeRule.onNodeWithTag("clender_open_navigation_drawer").performClick()
        composeRule.onNodeWithTag("clender_drawer_ai").performClick()
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            viewModel.state.value.loadStatus != ConversationLoadStatus.INACTIVE
        }
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            viewModel.state.value.loadStatus !in setOf(
                ConversationLoadStatus.INACTIVE,
                ConversationLoadStatus.LOADING
            )
        }
        assertEquals(ConversationLoadStatus.READY, viewModel.state.value.loadStatus)

        val state = viewModel.state.value
        assertEquals(1, state.conversations.size)
        assertEquals(state.conversations.single(), state.activeConversation)
        assertTrue(state.messages.isEmpty())
        assertEquals(
            listOf(state.activeConversation?.id),
            runBlocking {
                application().container.conversationRepository.listConversations().map { it.id }
            }
        )
        assertEquals(
            state.activeConversation?.id,
            runBlocking {
                application().container.activeConversationStore.activeConversationId.first()
            }
        )
        assertTrue(dataStoreFile().exists())

        composeRule.onNodeWithTag("clender_open_navigation_drawer").performClick()
        composeRule.onNodeWithTag("clender_drawer_calendar").performClick()
        composeRule.onNodeWithTag("clender_open_navigation_drawer").performClick()
        composeRule.onNodeWithTag("clender_drawer_ai").performClick()
        composeRule.waitForIdle()

        assertEquals(
            1,
            runBlocking { application().container.conversationRepository.listConversations().size }
        )
    }

    private fun application(): ClenderApplication =
        composeRule.activity.application as ClenderApplication

    private fun dataStoreFile(): File =
        File(application().filesDir, "datastore/clender.preferences_pb")

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
    }
}
