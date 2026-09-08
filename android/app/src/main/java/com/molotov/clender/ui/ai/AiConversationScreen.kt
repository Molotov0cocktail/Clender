package com.molotov.clender.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molotov.clender.R
import com.molotov.clender.ui.foundation.AdaptiveLayoutPolicy

private data class ConversationSubmission(
    val state: AiSubmissionUiState,
    val actions: AiConversationSubmissionActions
)

@Composable
fun AiConversationScreen(
    viewModel: ConversationViewModel,
    modifier: Modifier = Modifier,
    submissionState: AiSubmissionUiState? = null,
    submissionActions: AiConversationSubmissionActions = AiConversationSubmissionActions(),
    paneBackEnabled: Boolean = true
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (state.loadStatus) {
        ConversationLoadStatus.INACTIVE,
        ConversationLoadStatus.LOADING -> ConversationLoading(modifier)

        ConversationLoadStatus.ERROR -> ConversationError(
            errorCode = state.errorCode,
            onRetry = viewModel.retry,
            modifier = modifier
        )

        ConversationLoadStatus.READY -> ConversationReady(
            state = state,
            viewModel = viewModel,
            paneBackEnabled = paneBackEnabled,
            submission = submissionState?.let { ConversationSubmission(it, submissionActions) },
            modifier = modifier
        )
    }
    ConversationDialogHost(state, viewModel)
}

@Composable
private fun ConversationReady(
    state: ConversationUiState,
    viewModel: ConversationViewModel,
    paneBackEnabled: Boolean,
    submission: ConversationSubmission?,
    modifier: Modifier
) {
    val widthDp = LocalConfiguration.current.screenWidthDp.coerceAtLeast(1)
    val active = state.activeConversation
    if (active == null) {
        ConversationError(
            errorCode = ConversationErrorCode.INITIALIZE_FAILED,
            onRetry = viewModel.retry,
            modifier = modifier
        )
        return
    }
    val operationInProgress = state.operation != ConversationOperation.IDLE
    Column(modifier.fillMaxSize()) {
        if (state.errorCode == ConversationErrorCode.OPERATION_FAILED) {
            Text(
                text = stringResource(R.string.conversation_operation_failed),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag("conversation_operation_error"),
                color = MaterialTheme.colorScheme.error
            )
        }
        ConversationAdaptiveLayout(
            layout = AdaptiveLayoutPolicy.forWidthDp(widthDp),
            compactPane = state.compactPane,
            actions = ConversationAdaptiveActions(
                onShowConversationList = viewModel.showConversationList,
                onBackToMessages = viewModel.showMessages,
                backEnabled = paneBackEnabled
            ),
            content = ConversationAdaptiveContent(
                conversationList = {
                    ConversationListPane(
                        conversations = state.conversations,
                        activeConversationId = active.id,
                        operationInProgress = operationInProgress,
                        actions = ConversationListActions(
                            onSelect = viewModel.selectConversation,
                            onCreate = viewModel.createConversation,
                            onRename = viewModel.requestRename,
                            onClear = viewModel.requestClear,
                            onDelete = viewModel.requestDelete
                        )
                    )
                },
                messagePane = {
                    ReadyMessagePane(
                        conversationId = active.id,
                        state = state,
                        submission = submission
                    )
                }
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ReadyMessagePane(
    conversationId: String,
    state: ConversationUiState,
    submission: ConversationSubmission?
) {
    Column(Modifier.fillMaxSize()) {
        ConversationMessagePane(
            conversationId = conversationId,
            messages = state.messages,
            modifier = Modifier.weight(1f)
        )
        if (submission == null) {
            Text(
                text = stringResource(R.string.conversation_offline_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .testTag("conversation_offline_hint"),
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            AiComposer(
                state = conversationSubmissionState(submission.state, conversationId),
                actions = AiComposerActions(
                    onDraftChange = submission.actions.onDraftChange,
                    onSend = {
                        submission.actions.onSubmitConversation(conversationId)
                    },
                    onDismissStatus = submission.actions.onDismissStatus,
                    onOpenSettings = submission.actions.onOpenSettings
                ),
                presentation = AiComposerPresentation(
                    approximateTokenCount = state.activeConversation?.tokenCount ?: 0,
                    executionFeedback = currentExecutionFeedback(
                        state.messages,
                        submission.state,
                        conversationId
                    ),
                    contextUsage = state.activeConversation?.let {
                        conversationContextUsage(it)
                    }
                )
            )
        }
    }
}

@Composable
private fun ConversationLoading(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("conversation_loading"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator()
            Text(stringResource(R.string.conversation_loading))
        }
    }
}

@Composable
private fun ConversationError(
    errorCode: ConversationErrorCode?,
    onRetry: () -> Unit,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("conversation_error"),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(
                    if (errorCode == ConversationErrorCode.MESSAGES_FAILED) {
                        R.string.conversation_messages_failed
                    } else {
                        R.string.conversation_load_failed
                    }
                )
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("conversation_retry")
            ) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun ConversationDialogHost(state: ConversationUiState, viewModel: ConversationViewModel) {
    val failed = state.errorCode == ConversationErrorCode.OPERATION_FAILED
    when (val dialog = state.dialog) {
        is ConversationDialogState.Rename -> RenameConversationDialog(
            currentTitle = dialog.input,
            operationInProgress = state.operation == ConversationOperation.RENAMING,
            operationFailed = failed,
            onConfirm = { title ->
                viewModel.updateRenameTitle(title)
                viewModel.confirmRename()
            },
            onDismiss = viewModel.dismissDialog
        )

        is ConversationDialogState.ConfirmClear -> ClearConversationDialog(
            operationInProgress = state.operation == ConversationOperation.CLEARING,
            operationFailed = failed,
            onConfirm = viewModel.confirmClear,
            onDismiss = viewModel.dismissDialog
        )

        is ConversationDialogState.ConfirmDelete -> DeleteConversationDialog(
            operationInProgress = state.operation == ConversationOperation.DELETING,
            operationFailed = failed,
            onConfirm = viewModel.confirmDelete,
            onDismiss = viewModel.dismissDialog
        )

        null -> Unit
    }
}
