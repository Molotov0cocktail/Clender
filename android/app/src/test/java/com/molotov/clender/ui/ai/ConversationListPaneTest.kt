package com.molotov.clender.ui.ai

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [26, 36],
    application = ClenderApplication::class
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationListPaneTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val composeHost = RobolectricComposeHost()

    @Before
    fun startComposeHost() {
        composeHost.start()
    }

    @After
    fun closeComposeHost() {
        composeHost.close()
    }

    @Test
    fun listShowsTitleTokenCountAndSelectedSemanticsWithoutInternalIds() {
        setList(activeConversationId = SECOND_ID)

        composeRule.onNodeWithTag("conversation_list_pane").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_title_$FIRST_ID", useUnmergedTree = true)
            .assertTextContains("Planning 🌏")
        composeRule.onNodeWithTag("conversation_token_count_$FIRST_ID", useUnmergedTree = true)
            .assertTextContains("17", substring = true)
        composeRule.onNodeWithTag("conversation_row_$FIRST_ID").assertIsNotSelected()
        composeRule.onNodeWithTag("conversation_row_$SECOND_ID").assertIsSelected()
        composeRule.onNodeWithText(FIRST_ID).assertDoesNotExist()
    }

    @Test
    fun rowSelectionAndManagementActionsEmitOnlyTheirExplicitTargets() {
        val selections = mutableListOf<String>()
        val renames = mutableListOf<String>()
        val clears = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        var creates = 0
        setList(
            actions = ConversationListActions(
                onSelect = selections::add,
                onCreate = { creates += 1 },
                onRename = renames::add,
                onClear = clears::add,
                onDelete = deletes::add
            )
        )

        composeRule.onNodeWithTag("conversation_row_$SECOND_ID").performClick()
        composeRule.onNodeWithTag("conversation_create").performClick()
        composeRule.onNodeWithTag("conversation_rename_$FIRST_ID").performClick()
        composeRule.onNodeWithTag("conversation_clear_$FIRST_ID").performClick()
        composeRule.onNodeWithTag("conversation_delete_$FIRST_ID").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(SECOND_ID), selections)
            assertEquals(1, creates)
            assertEquals(listOf(FIRST_ID), renames)
            assertEquals(listOf(FIRST_ID), clears)
            assertEquals(listOf(FIRST_ID), deletes)
        }
    }

    @Test
    fun everyListActionHasClickSemanticsAndAtLeastFortyEightDpTarget() {
        setList()

        listOf(
            "conversation_create",
            "conversation_row_$FIRST_ID",
            "conversation_rename_$FIRST_ID",
            "conversation_clear_$FIRST_ID",
            "conversation_delete_$FIRST_ID"
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).assertHasClickAction()
            assertMinimumTouchTarget(tag)
        }
    }

    @Test
    fun busyStateDisablesAllMutatingActionsButKeepsSelectionAvailable() {
        setList(operationInProgress = true)

        composeRule.onNodeWithTag("conversation_create").assertIsNotEnabled()
        composeRule.onNodeWithTag("conversation_rename_$FIRST_ID").assertIsNotEnabled()
        composeRule.onNodeWithTag("conversation_clear_$FIRST_ID").assertIsNotEnabled()
        composeRule.onNodeWithTag("conversation_delete_$FIRST_ID").assertIsNotEnabled()
        composeRule.onNodeWithTag("conversation_row_$SECOND_ID")
            .assertHasClickAction()
            .performClick()
    }

    @Test
    fun emptyListHasBoundedLocalizedStateAndCreateRemainsReachable() {
        setList(conversations = emptyList(), activeConversationId = null)

        composeRule.onNodeWithTag("conversation_list_empty").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_create")
            .assertHasClickAction()
            .assertIsDisplayed()
    }

    @Test
    fun compactListActionsRemainReachableAtTwentySpAndDoubleFontScale() {
        setTestContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ClenderTheme(AppearanceUiState(ThemeMode.DARK, 20, 13)) {
                    ConversationListPane(
                        conversations = conversations().take(1),
                        activeConversationId = FIRST_ID,
                        operationInProgress = false,
                        actions = noOpListActions(),
                        modifier = Modifier.width(360.dp)
                    )
                }
            }
        }

        composeRule.onNodeWithTag("conversation_create").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_rename_$FIRST_ID").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_clear_$FIRST_ID").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_delete_$FIRST_ID").assertIsDisplayed()
    }

    private fun setList(
        conversations: List<Conversation> = conversations(),
        activeConversationId: String? = FIRST_ID,
        operationInProgress: Boolean = false,
        actions: ConversationListActions = noOpListActions()
    ) {
        setTestContent {
            ConversationListPane(
                conversations = conversations,
                activeConversationId = activeConversationId,
                operationInProgress = operationInProgress,
                actions = actions
            )
        }
    }

    private fun setTestContent(content: @Composable () -> Unit) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent(content = content)
        }
        composeRule.waitForIdle()
    }

    private fun assertMinimumTouchTarget(tag: String) {
        composeRule.onNodeWithTag(tag)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    private fun conversations(): List<Conversation> = listOf(
        Conversation(FIRST_ID, "Planning 🌏", Instant.parse("2026-08-31T00:00:00Z"), 17),
        Conversation(SECOND_ID, "第二个对话", Instant.parse("2026-08-31T00:01:00Z"), 0)
    )

    private fun noOpListActions(): ConversationListActions = ConversationListActions(
        onSelect = {},
        onCreate = {},
        onRename = {},
        onClear = {},
        onDelete = {}
    )

    private companion object {
        const val FIRST_ID = "11111111111111111111111111111111"
        const val SECOND_ID = "22222222222222222222222222222222"
    }
}
