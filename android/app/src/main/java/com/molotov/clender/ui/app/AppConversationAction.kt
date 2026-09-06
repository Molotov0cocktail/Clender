package com.molotov.clender.ui.app

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molotov.clender.R
import com.molotov.clender.ui.ai.ConversationLoadStatus
import com.molotov.clender.ui.ai.ConversationPane
import com.molotov.clender.ui.ai.ConversationViewModel
import com.molotov.clender.ui.foundation.AdaptiveLayoutPolicy
import com.molotov.clender.ui.foundation.PaneLayout
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.state.AppShellUiState

@Composable
internal fun AppConversationAction(shell: AppShellUiState, viewModel: ConversationViewModel?) {
    val layout = AdaptiveLayoutPolicy.forWidthDp(LocalConfiguration.current.screenWidthDp)
    val isAiRoot = shell.destination == AppDestination.AI && shell.childRoutes.isEmpty()
    if (!isAiRoot || viewModel == null || layout.aiPaneLayout != PaneLayout.SINGLE) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.loadStatus != ConversationLoadStatus.READY || state.activeConversation == null) return
    val showingList = state.compactPane == ConversationPane.LIST
    IconButton(
        onClick = if (showingList) viewModel.showMessages else viewModel.showConversationList,
        modifier = Modifier
            .testTag(if (showingList) "ai_back_to_messages" else "ai_show_conversation_list")
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
    ) {
        Icon(
            imageVector = if (showingList) {
                Icons.AutoMirrored.Filled.ArrowBack
            } else {
                Icons.AutoMirrored.Filled.List
            },
            contentDescription = stringResource(
                if (showingList) {
                    R.string.conversation_back_to_messages
                } else {
                    R.string.conversation_show_list
                }
            )
        )
    }
}
