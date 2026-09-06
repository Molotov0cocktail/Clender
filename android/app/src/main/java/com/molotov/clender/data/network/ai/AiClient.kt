package com.molotov.clender.data.network.ai

import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.domain.ai.AiRequestMessage

data class AiCompletionUsage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int)

data class AiCompletion(
    val content: String,
    val reasoningContent: String,
    val usage: AiCompletionUsage
)

interface AiClient {
    suspend fun fetchModels(settings: AiSettings, apiKey: CharArray): List<String>

    suspend fun complete(
        settings: AiSettings,
        apiKey: CharArray,
        messages: List<AiRequestMessage>
    ): AiCompletion

    fun cancelInFlight()
}

open class AiClientException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class AiConfigurationException(
    message: String = "AI configuration is incomplete",
    cause: Throwable? = null
) : AiClientException(message, cause)

class AiHttpException(val statusCode: Int, val safeBody: String) :
    AiClientException("AI request failed (HTTP $statusCode)")

class AiProtocolException : AiClientException("AI provider returned an invalid response")

class AiTimeoutException : AiClientException("AI request timed out")

class AiNetworkException : AiClientException {
    constructor() : super("AI network request failed")

    constructor(cause: Throwable) : super("AI network request failed", cause)
}

class AiResponseTooLargeException : AiClientException("AI response exceeded the size limit")

class AiRequestCancelledException : AiClientException("AI request was cancelled")
