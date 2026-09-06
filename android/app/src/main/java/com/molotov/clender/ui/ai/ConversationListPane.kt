package com.molotov.clender.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.core.model.Conversation

@Composable
fun ConversationListPane(
    conversations: List<Conversation>,
    activeConversationId: String?,
    operationInProgress: Boolean,
    actions: ConversationListActions,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
            .testTag("conversation_list_pane"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = actions.onCreate,
            enabled = !operationInProgress,
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 48.dp)
                .testTag("conversation_create")
        ) {
            Text(stringResource(R.string.conversation_create))
        }
        if (conversations.isEmpty()) {
            Text(
                text = stringResource(R.string.conversation_list_empty),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .testTag("conversation_list_empty"),
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("conversation_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(conversations, key = Conversation::id) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.id == activeConversationId,
                        operationInProgress = operationInProgress,
                        actions = actions
                    )
                }
            }
        }
    }
}

@Composable
internal fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    operationInProgress: Boolean,
    actions: ConversationListActions,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ConversationRowHeader(conversation, selected) {
                actions.onSelect(conversation.id)
            }
            ConversationRowActions(conversation.id, operationInProgress, actions)
        }
    }
}

@Composable
private fun ConversationRowHeader(
    conversation: Conversation,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .testTag("conversation_row_${conversation.id}")
            .semantics { this.selected = selected }
            .clickable(onClick = onSelect),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = conversation.title,
            modifier = Modifier.testTag("conversation_title_${conversation.id}"),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(R.string.conversation_token_count, conversation.tokenCount),
            modifier = Modifier.testTag("conversation_token_count_${conversation.id}"),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ConversationRowActions(
    conversationId: String,
    operationInProgress: Boolean,
    actions: ConversationListActions
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ConversationAction(
            stringResource(R.string.conversation_rename),
            "conversation_rename_$conversationId",
            !operationInProgress
        ) { actions.onRename(conversationId) }
        ConversationAction(
            stringResource(R.string.conversation_clear),
            "conversation_clear_$conversationId",
            !operationInProgress
        ) { actions.onClear(conversationId) }
        ConversationAction(
            stringResource(R.string.conversation_delete),
            "conversation_delete_$conversationId",
            !operationInProgress
        ) { actions.onDelete(conversationId) }
    }
}

@Composable
private fun ConversationAction(text: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .testTag(tag)
    ) {
        Text(text)
    }
}
