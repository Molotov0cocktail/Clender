package com.molotov.clender.data.network.ai

import com.molotov.clender.data.settings.AiEndpointPolicy
import okhttp3.HttpUrl

object AiEndpointValidator {
    fun modelsUrl(endpoint: String): HttpUrl = resolve(endpoint, "models")

    fun chatUrl(endpoint: String): HttpUrl = resolve(endpoint, "chat/completions")

    private fun resolve(endpoint: String, relativePath: String): HttpUrl = try {
        AiEndpointPolicy.resolve(endpoint, relativePath)
    } catch (error: IllegalArgumentException) {
        throw AiConfigurationException(error.message ?: "AI endpoint is invalid", error)
    }
}
