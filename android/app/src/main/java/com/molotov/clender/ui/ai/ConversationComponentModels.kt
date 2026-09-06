package com.molotov.clender.ui.ai

import androidx.compose.runtime.Composable

data class ConversationListActions(
    val onSelect: (String) -> Unit,
    val onCreate: () -> Unit,
    val onRename: (String) -> Unit,
    val onClear: (String) -> Unit,
    val onDelete: (String) -> Unit
)

data class ConversationAdaptiveActions(
    val onShowConversationList: () -> Unit,
    val onBackToMessages: () -> Unit,
    val backEnabled: Boolean = true
)

data class ConversationAdaptiveContent(
    val conversationList: @Composable () -> Unit,
    val messagePane: @Composable () -> Unit
)
