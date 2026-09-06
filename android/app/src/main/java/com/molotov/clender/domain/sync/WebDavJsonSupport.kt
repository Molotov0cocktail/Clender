package com.molotov.clender.domain.sync

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val STRICT_JSON = Json {
    isLenient = false
    ignoreUnknownKeys = false
    explicitNulls = true
}

internal fun decodeWebDavUtf8(payload: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(payload))
        .toString()
} catch (_: CharacterCodingException) {
    throw WebDavDocumentException("WebDAV document is not valid UTF-8")
}

internal fun parseWebDavJson(source: String): JsonElement = try {
    STRICT_JSON.parseToJsonElement(source)
} catch (_: SerializationException) {
    throw WebDavDocumentException("WebDAV document is not valid JSON")
} catch (_: IllegalArgumentException) {
    throw WebDavDocumentException("WebDAV document is not valid JSON")
}

internal fun requireUnicodeScalars(value: String) {
    var index = 0
    while (index < value.length) {
        val character = value[index]
        when {
            Character.isHighSurrogate(character) -> {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                    throw WebDavDocumentException("WebDAV text contains an unpaired surrogate")
                }
                index += 2
            }

            Character.isLowSurrogate(character) -> throw WebDavDocumentException(
                "WebDAV text contains an unpaired surrogate"
            )

            else -> index += 1
        }
    }
}

internal fun Instant.hasWebDavMicrosecondPrecision(): Boolean = nano % NANOS_PER_MICROSECOND == 0

private const val NANOS_PER_MICROSECOND = 1_000
