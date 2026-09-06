package com.molotov.clender.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole

private const val MESSAGE_BUBBLE_WIDTH_FRACTION = 0.86f

private data class MessageBubbleStyle(
    val alignment: Alignment.Horizontal,
    val color: Color,
    val tag: String
)

@Composable
fun ConversationMessagePane(
    conversationId: String,
    messages: List<Message>,
    modifier: Modifier = Modifier
) {
    var expandedThinkingIds by remember(conversationId) { mutableStateOf(emptySet<Long>()) }
    val orderedMessages = remember(messages) {
        messages.sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
    }

    if (orderedMessages.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .testTag("conversation_messages_empty"),
            contentAlignment = Alignment.Center
        ) {
            Text(text = stringResource(R.string.conversation_messages_empty))
        }
        return
    }

    val saveableStateHolder = rememberSaveableStateHolder()
    saveableStateHolder.SaveableStateProvider("conversation-messages:$conversationId") {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .testTag("conversation_messages_list"),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(orderedMessages, key = Message::id) { message ->
                ConversationMessageItem(
                    message = message,
                    expandedThinkingIds = expandedThinkingIds,
                    onToggleThinking = { messageId ->
                        expandedThinkingIds = if (messageId in expandedThinkingIds) {
                            expandedThinkingIds - messageId
                        } else {
                            expandedThinkingIds + messageId
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ConversationMessageItem(
    message: Message,
    expandedThinkingIds: Set<Long>,
    onToggleThinking: (Long) -> Unit
) {
    when (message.role) {
        MessageRole.USER -> MessageBubble(
            message = message,
            roleLabel = stringResource(R.string.conversation_role_user),
            style = MessageBubbleStyle(
                alignment = Alignment.End,
                color = MaterialTheme.colorScheme.primaryContainer,
                tag = "conversation_message_user_${message.id}"
            )
        )

        MessageRole.ASSISTANT -> MessageBubble(
            message = message,
            roleLabel = stringResource(R.string.conversation_role_assistant),
            style = MessageBubbleStyle(
                alignment = Alignment.Start,
                color = MaterialTheme.colorScheme.secondaryContainer,
                tag = "conversation_message_assistant_${message.id}"
            )
        )

        MessageRole.THINK -> ThinkingMessage(
            message = message,
            expanded = message.id in expandedThinkingIds,
            onToggle = { onToggleThinking(message.id) }
        )
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    roleLabel: String,
    style: MessageBubbleStyle,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (style.alignment == Alignment.End) {
            Arrangement.End
        } else {
            Arrangement.Start
        }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(MESSAGE_BUBBLE_WIDTH_FRACTION)
                .testTag(style.tag)
                .semantics { contentDescription = roleLabel },
            color = style.color,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = roleLabel, style = MaterialTheme.typography.labelMedium)
                Text(text = message.content, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
internal fun ThinkingMessage(
    message: Message,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val roleLabel = stringResource(R.string.conversation_role_thinking)
    val actionLabel = stringResource(
        if (expanded) {
            R.string.conversation_thinking_collapse
        } else {
            R.string.conversation_thinking_expand
        }
    )
    val expansionState = stringResource(
        if (expanded) {
            R.string.conversation_thinking_expanded
        } else {
            R.string.conversation_thinking_collapsed
        }
    )
    Surface(
        modifier = modifier
            .fillMaxWidth(MESSAGE_BUBBLE_WIDTH_FRACTION)
            .testTag("conversation_message_think_${message.id}")
            .semantics { contentDescription = roleLabel },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            TextButton(
                onClick = onToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .testTag("conversation_thinking_toggle_${message.id}")
                    .semantics {
                        contentDescription = actionLabel
                        role = androidx.compose.ui.semantics.Role.Button
                        stateDescription = expansionState
                    },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            ) {
                Text(text = roleLabel)
            }
            if (expanded) {
                Text(
                    text = message.content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .testTag("conversation_thinking_content_${message.id}"),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
