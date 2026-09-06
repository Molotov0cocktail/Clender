package com.molotov.clender.ui.ai

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.app.ai.AiCoordinatorError
import com.molotov.clender.app.ai.AiCoordinatorState
import com.molotov.clender.app.ai.AiSubmissionDecision
import com.molotov.clender.app.ai.AiSubmissionGateway
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AdaptiveLayoutPolicy
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.Instant
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class C3AiAdaptiveAccessibilityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val composeHost = RobolectricComposeHost()
    private val viewModels = mutableListOf<AiSubmissionViewModel>()

    @Before
    fun startHost() = composeHost.start()

    @After
    fun closeHost() {
        viewModels.forEach(::clearViewModel)
        viewModels.clear()
        composeHost.close()
    }

    @Test
    fun dynamicBoundaryChangesKeepDraftAndOneAcceptedSubmission() {
        val gateway = FakeGateway(autoComplete = false)
        var runtimeReads = 0
        val viewModel = track(
            AiSubmissionViewModel {
                runtimeReads += 1
                gateway
            }
        )
        viewModel.activate()
        viewModel.updateDraft("  preserve 🌏 draft  ")
        var width by mutableStateOf(599)

        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                val state by viewModel.state.collectAsState()
                ClenderTheme(AppearanceUiState(ThemeMode.SYSTEM, 20, 13)) {
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, 2f)
                    ) {
                        ConversationAdaptiveLayout(
                            layout = AdaptiveLayoutPolicy.forWidthDp(width),
                            compactPane = ConversationPane.MESSAGES,
                            actions = ConversationAdaptiveActions({}, {}),
                            content = ConversationAdaptiveContent(
                                conversationList = {
                                    ConversationListPane(emptyList(), null, false, listActions())
                                },
                                messagePane = {
                                    AiComposer(
                                        state = state,
                                        actions = composerActions(viewModel),
                                        modifier = Modifier.width(width.dp)
                                    )
                                }
                            ),
                            modifier = Modifier.width(width.dp)
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        listOf(600, 839, 840, 599).forEach { nextWidth ->
            composeRule.runOnUiThread { width = nextWidth }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("ai_composer_input").assertIsDisplayed()
        }

        viewModel.submit(HEX_ID)
        viewModel.submit(HEX_ID)
        composeRule.waitForIdle()
        assertEquals(listOf(HEX_ID to "preserve 🌏 draft"), gateway.submissions)
        assertEquals(1, gateway.networkCalls)
        gateway.finish(AiSubmissionDecision.ACCEPTED)
        composeRule.waitForIdle()
        assertEquals("", viewModel.state.value.draft)
        assertEquals(1, runtimeReads)
    }

    @Test
    fun compactPaneSwitchRestoresConversationAndMessageScrollPositions() {
        val conversations = (0..20).map { conversation(it) }
        val messages = (0..24).map { message(100L + it, "message $it") }
        var pane by mutableStateOf(ConversationPane.LIST)

        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                ConversationAdaptiveLayout(
                    layout = AdaptiveLayoutPolicy.forWidthDp(360),
                    compactPane = pane,
                    actions = ConversationAdaptiveActions({}, { pane = ConversationPane.MESSAGES }),
                    content = ConversationAdaptiveContent(
                        conversationList = {
                            ConversationListPane(
                                conversations = conversations,
                                activeConversationId = conversations.first().id,
                                operationInProgress = false,
                                actions = listActions()
                            )
                        },
                        messagePane = {
                            ConversationMessagePane(
                                conversationId = conversations.first().id,
                                messages = messages,
                                modifier = Modifier.width(360.dp)
                            )
                        }
                    )
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("conversation_list_pane").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_list")
            .performScrollToIndex(conversations.lastIndex)
        composeRule.onNodeWithTag("conversation_row_${conversations.last().id}")
            .assertIsDisplayed()

        composeRule.onNodeWithTag("ai_show_conversation_list").assertDoesNotExist()
        composeRule.runOnUiThread { pane = ConversationPane.MESSAGES }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("conversation_messages_list").performScrollToNode(
            hasTestTag("conversation_message_assistant_${messages.last().id}")
        )
        composeRule.onNodeWithTag("conversation_message_assistant_${messages.last().id}")
            .assertIsDisplayed()

        composeRule.runOnUiThread { pane = ConversationPane.LIST }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("conversation_row_${conversations.last().id}").assertIsDisplayed()
        composeRule.runOnUiThread { pane = ConversationPane.MESSAGES }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("conversation_message_assistant_${messages.last().id}")
            .assertIsDisplayed()
    }

    @Test
    fun thinkingCollapseUsesOneButtonAndHidesBodyFromMergedAndUnmergedSemantics() {
        val body = "private thinking body"
        setMessages(listOf(message(7, body, MessageRole.THINK)))

        val toggle = composeRule.onNodeWithTag(
            "conversation_thinking_toggle_7",
            useUnmergedTree = true
        )
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode()
        assertEquals(Role.Button, toggle.config.getOrNull(SemanticsProperties.Role))
        assertTrue(toggle.config.contains(SemanticsActions.OnClick))
        assertTrue(
            toggle.config.getOrNull(SemanticsProperties.StateDescription).orEmpty().isNotBlank()
        )
        composeRule.onNodeWithText(body, useUnmergedTree = true).assertDoesNotExist()

        composeRule.onNodeWithTag("conversation_thinking_toggle_7").performClick()
        composeRule.onNodeWithText(body, useUnmergedTree = true).assertIsDisplayed()
        val expanded = composeRule.onNodeWithTag("conversation_thinking_toggle_7")
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertNotEquals(toggle.config.getOrNull(SemanticsProperties.StateDescription), expanded)
    }

    @Test
    fun composerAndFiniteStatusExposePoliteLiveRegionWithoutRawFailureDetails() {
        val raw = "https://private.example/raw-provider-body"
        setComposer(
            AiSubmissionUiState(
                isActive = true,
                draft = "safe draft",
                status = AiSubmissionStatus.FAILED,
                errorCode = AiCoordinatorError.PROVIDER
            )
        )
        val status = composeRule.onNodeWithTag("ai_composer_error").fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, status.getOrNull(SemanticsProperties.LiveRegion))
        assertTrue(status.getOrNull(SemanticsProperties.StateDescription).orEmpty().isNotBlank())
        assertFalse(status.toString().contains(raw))
        composeRule.onNodeWithTag("ai_composer_input")
            .assertHeightIsAtLeast(48.dp)
            .performTextInput(" appended")
        composeRule.onNodeWithTag("ai_composer_send")
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        setComposer(
            AiSubmissionUiState(isActive = true, status = AiSubmissionStatus.WORKING)
        )
        val working = composeRule.onNodeWithTag("ai_composer_working").fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, working.getOrNull(SemanticsProperties.LiveRegion))
        assertFalse(working.toString().contains(raw))
    }

    @Test
    fun rtlAllAdaptiveBoundariesKeepPaneAndComposerTargetsReachableAcrossThemes() {
        var width by mutableStateOf(599)
        var theme by mutableStateOf(ThemeMode.LIGHT)
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                    LocalDensity provides Density(LocalDensity.current.density, 2f)
                ) {
                    ClenderTheme(AppearanceUiState(theme, 20, 8)) {
                        ConversationAdaptiveLayout(
                            layout = AdaptiveLayoutPolicy.forWidthDp(width),
                            compactPane = ConversationPane.MESSAGES,
                            actions = ConversationAdaptiveActions({}, {}),
                            content = ConversationAdaptiveContent(
                                conversationList = { Text("list") },
                                messagePane = {
                                    AiComposer(
                                        AiSubmissionUiState(isActive = true, draft = "draft"),
                                        composerActions(),
                                        modifier = Modifier.width(width.dp)
                                    )
                                }
                            ),
                            modifier = Modifier.width(width.dp)
                        )
                    }
                }
            }
        }
        listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM).forEach { nextTheme ->
            theme = nextTheme
            listOf(599, 600, 839, 840).forEach { nextWidth ->
                composeRule.runOnUiThread { width = nextWidth }
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("ai_composer_input").assertIsDisplayed()
                    .assertHeightIsAtLeast(48.dp)
                if (nextWidth < 600) {
                    composeRule.onNodeWithTag("ai_conversation_single_pane").assertIsDisplayed()
                } else {
                    composeRule.onNodeWithTag("ai_conversation_two_pane").assertIsDisplayed()
                }
            }
        }
    }

    private fun setMessages(messages: List<Message>) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                ClenderTheme(AppearanceUiState(ThemeMode.LIGHT, 13, 13)) {
                    ConversationMessagePane(
                        conversationId = "c3-conversation",
                        messages = messages,
                        modifier = Modifier.width(360.dp)
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun setComposer(state: AiSubmissionUiState) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                ClenderTheme(AppearanceUiState(ThemeMode.LIGHT, 13, 13)) {
                    AiComposer(state, composerActions(), modifier = Modifier.width(360.dp))
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun composerActions(viewModel: AiSubmissionViewModel? = null) = AiComposerActions(
        onDraftChange = { viewModel?.updateDraft(it) },
        onSend = { viewModel?.submit(HEX_ID) },
        onDismissStatus = {},
        onOpenSettings = {}
    )

    private fun listActions() = ConversationListActions({}, {}, {}, {}, {})

    private fun track(viewModel: AiSubmissionViewModel) = viewModel.also(viewModels::add)

    private fun clearViewModel(viewModel: AiSubmissionViewModel) {
        ViewModel::class.java.declaredMethods.single {
            it.name.startsWith("clear") && it.parameterCount == 0
        }.also { it.isAccessible = true }.invoke(viewModel)
    }

    private fun conversation(index: Int) = com.molotov.clender.core.model.Conversation(
        id = "conversation-$index",
        title = "Conversation $index",
        createdAt = Instant.parse("2026-08-31T00:00:00Z").plusSeconds(index.toLong()),
        tokenCount = index
    )

    private fun message(id: Long, content: String, role: MessageRole = MessageRole.ASSISTANT) =
        Message(
            id = id,
            conversationId = "c3-conversation",
            role = role,
            content = content,
            timestamp = Instant.parse("2026-08-31T00:00:00Z").plusSeconds(id)
        )

    private class FakeGateway(private val autoComplete: Boolean) : AiSubmissionGateway {
        private val coordinator = MutableStateFlow<AiCoordinatorState>(AiCoordinatorState.Idle)
        override val state: StateFlow<AiCoordinatorState> = coordinator
        val submissions = mutableListOf<Pair<String, String>>()
        var networkCalls = 0
        private var continuation: ((AiSubmissionDecision) -> Unit)? = null

        override suspend fun submit(conversationId: String, message: String): AiSubmissionDecision {
            submissions += conversationId to message
            networkCalls += 1
            if (autoComplete) return AiSubmissionDecision.ACCEPTED
            return kotlinx.coroutines.suspendCancellableCoroutine { c ->
                continuation = { value -> c.resume(value) }
            }
        }

        fun finish(decision: AiSubmissionDecision) {
            continuation?.invoke(decision)
            continuation = null
        }

        override fun acknowledgeTerminal() = Unit
    }

    private companion object {
        const val HEX_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
