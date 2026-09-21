package com.molotov.clender.data.network.ai

class AiAccountedException(
    val failure: AiClientException,
    val usage: AiCompletionUsage,
    cause: Throwable = failure
) : AiClientException("AI request failed after a metered response", cause)
