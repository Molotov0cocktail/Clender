package com.molotov.clender.app.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AiContextUsage(val conversationId: String, val inputTokens: Int, val contextWindow: Int)

internal val emptyAiContextUsage = MutableStateFlow<AiContextUsage?>(null).asStateFlow()
