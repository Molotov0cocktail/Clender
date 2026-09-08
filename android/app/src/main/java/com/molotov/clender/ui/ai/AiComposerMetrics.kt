package com.molotov.clender.ui.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.molotov.clender.R

private const val PERCENT_SCALE = 100L

@Composable
internal fun AiComposerMetrics(presentation: AiComposerPresentation, modifier: Modifier) {
    Column(modifier) {
        if (presentation.approximateTokenCount >= 0) {
            val usage = presentation.contextUsage?.takeIf {
                it.contextWindow > 0 && it.inputTokens in 0..it.contextWindow
            }
            val contextText = if (usage == null) {
                stringResource(R.string.ai_context_unknown)
            } else {
                stringResource(
                    R.string.ai_context_estimate,
                    usage.inputTokens,
                    usage.contextWindow,
                    usage.inputTokens.toLong() * PERCENT_SCALE / usage.contextWindow
                )
            }
            val contextDescription = stringResource(R.string.ai_context_explanation, contextText)
            Text(
                text = contextText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .testTag("ai_conversation_context_usage")
                    .semantics { contentDescription = contextDescription }
            )
            val tokenLabel = stringResource(
                R.string.ai_token_count_cumulative,
                presentation.approximateTokenCount
            )
            val tokenDescription = stringResource(
                R.string.ai_token_count_explanation,
                presentation.approximateTokenCount
            )
            Text(
                text = tokenLabel,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .testTag("ai_conversation_token_count")
                    .semantics { contentDescription = tokenDescription }
            )
        }
    }
}
