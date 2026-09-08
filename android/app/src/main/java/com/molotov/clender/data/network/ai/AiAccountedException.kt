package com.molotov.clender.data.network.ai

class AiAccountedException(val failure: AiClientException, val usage: AiCompletionUsage) :
    AiClientException("AI correction failed after a completed response", failure)
