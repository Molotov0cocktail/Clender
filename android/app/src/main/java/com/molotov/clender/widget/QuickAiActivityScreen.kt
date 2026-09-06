package com.molotov.clender.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.ui.ai.AiComposer
import com.molotov.clender.ui.ai.AiComposerActions
import com.molotov.clender.ui.ai.AiComposerPresentation
import com.molotov.clender.ui.ai.AiSubmissionStatus
import com.molotov.clender.ui.ai.AiSubmissionUiState
import com.molotov.clender.ui.ai.ConversationLoadStatus
import com.molotov.clender.ui.ai.ConversationUiState
import com.molotov.clender.ui.ai.QuickAiActions

@Composable
internal fun QuickAiActivityScreen(
    conversationState: ConversationUiState,
    submissionState: AiSubmissionUiState,
    onRetry: () -> Unit,
    actions: QuickAiActions
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        QuickAiActivityContent(conversationState, submissionState, onRetry, actions)
    }
}

@Composable
private fun QuickAiActivityContent(
    conversationState: ConversationUiState,
    submissionState: AiSubmissionUiState,
    onRetry: () -> Unit,
    actions: QuickAiActions
) {
    val title = stringResource(R.string.screen_quick_ai)
    val ready = conversationState.loadStatus == ConversationLoadStatus.READY &&
        !conversationState.activeConversation?.id.isNullOrBlank()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .semantics { paneTitle = title }
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(12.dp).testTag("quick_ai_activity_heading")
                .semantics { heading() }
        )
        if (!ready) {
            QuickAiReadiness(conversationState.loadStatus, onRetry)
        }
        QuickAiNavigationButton(
            "quick_ai_back",
            stringResource(R.string.semantics_navigate_back),
            actions.onBack
        )
        QuickAiActivityComposer(submissionState, ready, actions)
        QuickAiNavigationButton(
            "quick_ai_open_conversation",
            stringResource(R.string.quick_ai_open_conversation),
            actions.onOpenConversation
        )
        if (submissionState.status != AiSubmissionStatus.UNCONFIGURED) {
            QuickAiNavigationButton(
                "quick_ai_activity_open_settings",
                stringResource(R.string.ai_open_settings),
                actions.onOpenSettings
            )
        }
    }
}

@Composable
private fun QuickAiActivityComposer(
    state: AiSubmissionUiState,
    ready: Boolean,
    actions: QuickAiActions
) {
    AiComposer(
        state = state.copy(isActive = state.isActive && ready),
        actions = AiComposerActions(
            actions.onDraftChange,
            actions.onSend,
            actions.onDismissStatus,
            actions.onOpenSettings
        ),
        presentation = AiComposerPresentation(
            approximateTokenCount = -1,
            tagPrefix = "quick_ai",
            scrollable = false
        )
    )
}

@Composable
private fun QuickAiNavigationButton(tag: String, label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag(tag)
    ) {
        Text(label)
    }
}

@Composable
private fun QuickAiReadiness(status: ConversationLoadStatus, onRetry: () -> Unit) {
    val failed = status == ConversationLoadStatus.ERROR
    val label = stringResource(
        if (failed) R.string.ai_error_internal else R.string.conversation_loading
    )
    Text(
        label,
        modifier = Modifier.padding(horizontal = 12.dp)
            .testTag(if (failed) "quick_ai_activity_error" else "quick_ai_activity_loading")
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = label
            }
    )
    if (failed) {
        TextButton(
            onClick = onRetry,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("quick_ai_activity_retry")
        ) {
            Text(stringResource(R.string.action_retry))
        }
    }
}
