package com.molotov.clender.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.molotov.clender.R

@Composable
fun QuickAiScreen(
    state: AiSubmissionUiState,
    actions: QuickAiActions,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(
            onClick = actions.onBack,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("quick_ai_back")
        ) {
            Text(stringResource(R.string.semantics_navigate_back))
        }
        AiComposer(
            state = state,
            actions = AiComposerActions(
                onDraftChange = actions.onDraftChange,
                onSend = actions.onSend,
                onDismissStatus = actions.onDismissStatus,
                onOpenSettings = actions.onOpenSettings
            ),
            presentation = AiComposerPresentation(
                approximateTokenCount = -1,
                tagPrefix = "quick_ai",
                scrollable = false
            ),
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(
            onClick = actions.onOpenConversation,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("quick_ai_open_conversation")
        ) {
            Text(stringResource(R.string.quick_ai_open_conversation))
        }
    }
}
