package com.molotov.clender.data.network.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

data class AiModelCapabilities(val contextWindow: Int? = null, val maxOutputTokens: Int? = null)

data class AiModelDescriptor(
    val id: String,
    val capabilities: AiModelCapabilities = AiModelCapabilities()
)

internal fun JsonObject.modelCapabilities(): AiModelCapabilities {
    val provider = this["top_provider"] as? JsonObject
    val context = provider?.positiveInt("context_length")
        ?: positiveInt("context_length") ?: positiveInt("context_window")
    val output = provider?.positiveInt("max_completion_tokens")
        ?: positiveInt("max_output_tokens") ?: positiveInt("max_completion_tokens")
    return AiModelCapabilities(context, output)
}

private fun JsonObject.positiveInt(name: String): Int? = (this[name] as? JsonPrimitive)
    ?.takeUnless(JsonPrimitive::isString)?.intOrNull?.takeIf { it > 0 }
