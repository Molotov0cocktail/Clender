package com.molotov.clender.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.app.ai.AiContextUsage
import com.molotov.clender.app.ai.AiCoordinatorError

private const val INCOMPLETE_REQUEST_RECEIPT = "AI 请求未完成，本轮未修改日程。"

data class AiComposerActions(
    val onDraftChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onDismissStatus: () -> Unit,
    val onOpenSettings: () -> Unit
)

data class AiComposerPresentation(
    val approximateTokenCount: Int = 0,
    val tagPrefix: String = "ai_composer",
    val scrollable: Boolean = true,
    val executionFeedback: String? = null,
    val contextUsage: AiContextUsage? = null
)

data class AiConversationSubmissionActions(
    val onDraftChange: (String) -> Unit = {},
    val onSubmitConversation: (String) -> Unit = {},
    val onDismissStatus: () -> Unit = {},
    val onOpenSettings: () -> Unit = {}
)

data class QuickAiActions(
    val onDraftChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onDismissStatus: () -> Unit,
    val onOpenConversation: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onBack: () -> Unit
)

@Composable
fun AiComposer(
    state: AiSubmissionUiState,
    actions: AiComposerActions,
    modifier: Modifier = Modifier,
    presentation: AiComposerPresentation = AiComposerPresentation()
) {
    val working = state.status == AiSubmissionStatus.WORKING
    val canSend = state.isActive && state.draft.isNotBlank() && !working
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (presentation.scrollable) {
                    Modifier.verticalScroll(rememberScrollState())
                } else {
                    Modifier
                }
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SubmissionStatus(
            state,
            actions.onDismissStatus,
            actions.onOpenSettings,
            presentation.tagPrefix,
            presentation.executionFeedback
        )
        OutlinedTextField(
            value = state.draft,
            onValueChange = actions.onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("${presentation.tagPrefix}_input"),
            enabled = state.isActive && !working,
            minLines = 2,
            maxLines = 5,
            label = { Text(stringResource(R.string.ai_composer_input)) }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AiComposerMetrics(presentation, Modifier.weight(1f))
            TextButton(
                onClick = actions.onSend,
                enabled = canSend,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("${presentation.tagPrefix}_send")
            ) {
                Text(stringResource(R.string.ai_composer_send))
            }
        }
    }
}

@Composable
private fun SubmissionStatus(
    state: AiSubmissionUiState,
    onDismissStatus: () -> Unit,
    onOpenSettings: () -> Unit,
    tagPrefix: String,
    executionFeedback: String?
) {
    val defaultText = statusText(state) ?: return
    val statusText = feedbackStatusText(state.status, executionFeedback, defaultText)
    val statusTag = when (state.status) {
        AiSubmissionStatus.WORKING -> "${tagPrefix}_working"

        AiSubmissionStatus.COMPLETED -> "${tagPrefix}_completed"

        AiSubmissionStatus.CANCELLED -> "${tagPrefix}_cancelled"

        AiSubmissionStatus.UNCONFIGURED -> "${tagPrefix}_unconfigured"

        AiSubmissionStatus.FAILED -> "${tagPrefix}_error"

        AiSubmissionStatus.INACTIVE,
        AiSubmissionStatus.IDLE -> return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(statusTag)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = statusText
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.status == AiSubmissionStatus.WORKING) {
                CircularProgressIndicator(Modifier.sizeIn(maxWidth = 24.dp, maxHeight = 24.dp))
            }
            Text(statusText, style = MaterialTheme.typography.bodySmall)
        }
        if (state.status == AiSubmissionStatus.UNCONFIGURED) {
            StatusAction(
                label = stringResource(R.string.ai_open_settings),
                tag = "${tagPrefix}_open_settings",
                action = onOpenSettings
            )
        } else if (state.status != AiSubmissionStatus.WORKING) {
            StatusAction(
                label = stringResource(R.string.ai_dismiss_status),
                tag = "${tagPrefix}_dismiss",
                action = onDismissStatus
            )
        }
    }
}

private fun feedbackStatusText(
    status: AiSubmissionStatus,
    feedback: String?,
    defaultText: String
): String = when {
    status != AiSubmissionStatus.COMPLETED && status != AiSubmissionStatus.FAILED -> defaultText
    feedback == INCOMPLETE_REQUEST_RECEIPT -> "$feedback\n$defaultText"
    else -> feedback?.takeIf(String::isNotBlank) ?: defaultText
}

@Composable
private fun StatusAction(label: String, tag: String, action: () -> Unit) {
    TextButton(
        onClick = action,
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .testTag(tag)
    ) {
        Text(label)
    }
}

@Composable
private fun statusText(state: AiSubmissionUiState): String? = when (state.status) {
    AiSubmissionStatus.INACTIVE,
    AiSubmissionStatus.IDLE -> null

    AiSubmissionStatus.WORKING -> stringResource(R.string.ai_status_working)

    AiSubmissionStatus.COMPLETED -> stringResource(R.string.ai_status_completed)

    AiSubmissionStatus.CANCELLED -> stringResource(R.string.ai_status_cancelled)

    AiSubmissionStatus.UNCONFIGURED -> stringResource(R.string.ai_status_unconfigured)

    AiSubmissionStatus.FAILED -> stringResource(errorResource(state.errorCode))
}

private fun errorResource(error: AiCoordinatorError?): Int = when (error) {
    AiCoordinatorError.CONFIGURATION -> R.string.ai_error_configuration

    AiCoordinatorError.TIMEOUT -> R.string.ai_error_timeout

    AiCoordinatorError.NETWORK -> R.string.ai_error_network

    AiCoordinatorError.AUTHENTICATION -> R.string.ai_error_authentication

    AiCoordinatorError.RATE_LIMIT -> R.string.ai_error_rate_limit

    AiCoordinatorError.REQUEST_PARAMETERS -> R.string.ai_error_request_parameters

    AiCoordinatorError.PROVIDER -> R.string.ai_error_provider

    AiCoordinatorError.INVALID_RESPONSE -> R.string.ai_error_invalid_response

    AiCoordinatorError.INTERNAL,
    null -> R.string.ai_error_internal
}
