package com.molotov.clender.ui.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.molotov.clender.ui.foundation.AdaptiveLayout
import com.molotov.clender.ui.foundation.PaneLayout

@Composable
fun ConversationAdaptiveLayout(
    layout: AdaptiveLayout,
    compactPane: ConversationPane,
    actions: ConversationAdaptiveActions,
    content: ConversationAdaptiveContent,
    modifier: Modifier = Modifier
) {
    val saveableStateHolder = rememberSaveableStateHolder()
    if (layout.aiPaneLayout == PaneLayout.TWO) {
        TwoPaneConversationLayout(content, modifier, saveableStateHolder)
    } else {
        SinglePaneConversationLayout(
            compactPane,
            actions,
            content,
            modifier,
            saveableStateHolder
        )
    }
}

@Composable
private fun TwoPaneConversationLayout(
    content: ConversationAdaptiveContent,
    modifier: Modifier,
    saveableStateHolder: SaveableStateHolder
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .testTag("ai_conversation_two_pane")
    ) {
        Box(
            Modifier
                .weight(LIST_PANE_WEIGHT)
                .fillMaxSize()
                .testTag("ai_conversation_list_pane")
        ) {
            saveableStateHolder.SaveableStateProvider(LIST_STATE_KEY) {
                content.conversationList()
            }
        }
        Box(
            Modifier
                .weight(MESSAGE_PANE_WEIGHT)
                .fillMaxSize()
                .testTag("ai_conversation_message_pane")
        ) {
            saveableStateHolder.SaveableStateProvider(MESSAGE_STATE_KEY) {
                content.messagePane()
            }
        }
    }
}

@Composable
private fun SinglePaneConversationLayout(
    compactPane: ConversationPane,
    actions: ConversationAdaptiveActions,
    content: ConversationAdaptiveContent,
    modifier: Modifier,
    saveableStateHolder: SaveableStateHolder
) {
    BackHandler(enabled = actions.backEnabled && compactPane == ConversationPane.LIST) {
        actions.onBackToMessages()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("ai_conversation_single_pane")
    ) {
        if (compactPane == ConversationPane.LIST) {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("ai_conversation_list_pane")
            ) {
                saveableStateHolder.SaveableStateProvider(LIST_STATE_KEY) {
                    content.conversationList()
                }
            }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("ai_conversation_message_pane")
            ) {
                saveableStateHolder.SaveableStateProvider(MESSAGE_STATE_KEY) {
                    content.messagePane()
                }
            }
        }
    }
}

private const val LIST_STATE_KEY = "ai-conversation-list"
private const val MESSAGE_STATE_KEY = "ai-conversation-messages"
private const val LIST_PANE_WEIGHT = 0.36f
private const val MESSAGE_PANE_WEIGHT = 0.64f
