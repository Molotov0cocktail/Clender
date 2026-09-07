@file:Suppress("TooManyFunctions")

package com.molotov.clender.domain.ai

import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.WallClockCodec
import com.molotov.clender.domain.event.AddEventCommand
import com.molotov.clender.domain.event.EventPatch
import com.molotov.clender.domain.event.FieldUpdate
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

data class AiResponseLimits(
    val maxPayloadChars: Int = 32_768,
    val maxOperations: Int = 16,
    val maxStringChars: Int = 4_096
)

sealed interface AiOperation {
    data class Add(val command: AddEventCommand) : AiOperation

    data class Update(val eventId: Long, val patch: EventPatch) : AiOperation

    data class Delete(val eventId: Long) : AiOperation

    data class Reply(val message: String) : AiOperation
}

sealed interface AiParseResult {
    data class Operations(val operations: List<AiOperation>) : AiParseResult

    data class PlainReply(val message: String) : AiParseResult

    data class Rejected(val userMessage: String) : AiParseResult
}

class AiResponseParser(
    private val limits: AiResponseLimits = AiResponseLimits(),
    private val json: Json = Json { ignoreUnknownKeys = false }
) {
    init {
        require(limits.maxPayloadChars > 0)
        require(limits.maxOperations > 0)
        require(limits.maxStringChars > 0)
    }

    @Suppress("ReturnCount")
    fun parse(content: String): AiParseResult {
        if (content.length > limits.maxPayloadChars) return rejected()
        val candidate = stripMarkdownFence(content)
        val root = try {
            json.parseToJsonElement(candidate)
        } catch (_: IllegalArgumentException) {
            return if (content.looksLikeOperationResponse()) {
                rejected()
            } else {
                AiParseResult.PlainReply(
                    content
                )
            }
        }
        if (root.containsOversizeString(limits.maxStringChars)) return rejected()
        return try {
            val objects = operationObjects(root) ?: return AiParseResult.PlainReply(content)
            if (objects.size > limits.maxOperations) return rejected()
            AiParseResult.Operations(objects.map(::parseOperation))
        } catch (_: RejectedOperationException) {
            rejected()
        }
    }

    private fun operationObjects(root: JsonElement): List<JsonObject>? = when (root) {
        is JsonObject -> when {
            "operations" in root -> {
                if (root.keys != setOf("operations")) throw RejectedOperationException()
                val values = root["operations"] as? JsonArray
                    ?: throw RejectedOperationException()
                values.map { it as? JsonObject ?: throw RejectedOperationException() }
            }

            "action" in root -> listOf(root)

            else -> null
        }

        is JsonArray -> {
            if (root.none { it is JsonObject && "action" in it }) return null
            root.map { it as? JsonObject ?: throw RejectedOperationException() }
        }

        else -> null
    }

    private fun parseOperation(value: JsonObject): AiOperation {
        val action = value.requiredString("action")
        return when (action) {
            "add" -> parseAdd(value)

            "update" -> parseUpdate(value)

            "delete" -> {
                value.requireOnly(DELETE_FIELDS)
                AiOperation.Delete(value.requiredPositiveId("event_id"))
            }

            "reply" -> {
                value.requireOnly(REPLY_FIELDS)
                val message = value.requiredString("message")
                if (message.isBlank()) throw RejectedOperationException()
                AiOperation.Reply(message)
            }

            else -> throw RejectedOperationException()
        }
    }

    @Suppress("ThrowsCount")
    private fun parseAdd(value: JsonObject): AiOperation.Add {
        value.requireOnly(ADD_FIELDS)
        val type = value.requiredEventType("event_type")
        val title = value.requiredString("title")
        if (title.isBlank()) throw RejectedOperationException()
        val start = value.requiredTime("start_time")
        val end = value.optionalTime("end_time")
        if (type == EventType.TIMESPAN && (end == null || !start.isBefore(end))) {
            throw RejectedOperationException()
        }
        if (type == EventType.REMINDER && end != null) throw RejectedOperationException()
        val duration = value.optionalNonNegativeInt("estimated_duration") ?: 0
        return AiOperation.Add(
            AddEventCommand(
                eventType = type,
                title = title.trim(),
                startTime = start,
                endTime = end,
                description = value.optionalString("description") ?: "",
                estimatedDurationMinutes = duration
            )
        )
    }

    private fun parseUpdate(value: JsonObject): AiOperation.Update {
        value.requireOnly(UPDATE_FIELDS)
        val eventId = value.requiredPositiveId("event_id")
        val patch = EventPatch(
            eventType = value.fieldEventType("event_type"),
            title = value.fieldTrimmedNonBlankString("title"),
            startTime = value.fieldTime("start_time"),
            endTime = value.fieldNullableTime("end_time"),
            description = value.fieldString("description"),
            estimatedDurationMinutes = value.fieldNonNegativeInt("estimated_duration")
        )
        if (patch.isEmpty()) throw RejectedOperationException()
        validateUpdateTemporalFields(patch)
        return AiOperation.Update(eventId, patch)
    }

    @Suppress("ThrowsCount")
    private fun validateUpdateTemporalFields(patch: EventPatch) {
        val requestedType = (patch.eventType as? FieldUpdate.Set)?.value
        val requestedStart = (patch.startTime as? FieldUpdate.Set)?.value
        val requestedEnd = (patch.endTime as? FieldUpdate.Set)?.value
        if (requestedStart != null &&
            requestedEnd != null &&
            !requestedStart.isBefore(requestedEnd)
        ) {
            throw RejectedOperationException()
        }
        if (requestedType == EventType.REMINDER && requestedEnd != null) {
            throw RejectedOperationException()
        }
        if (requestedType == EventType.TIMESPAN && requestedEnd == null) {
            throw RejectedOperationException()
        }
    }

    private fun rejected(): AiParseResult.Rejected =
        AiParseResult.Rejected("The assistant response could not be applied safely.")

    private companion object {
        val ADD_FIELDS = setOf(
            "action",
            "event_type",
            "title",
            "start_time",
            "end_time",
            "description",
            "estimated_duration"
        )
        val UPDATE_FIELDS = ADD_FIELDS + "event_id"
        val DELETE_FIELDS = setOf("action", "event_id")
        val REPLY_FIELDS = setOf("action", "message")
    }
}

private class RejectedOperationException : RuntimeException()

@Suppress("ReturnCount")
private fun stripMarkdownFence(value: String): String {
    val trimmed = value.trim()
    if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) return value
    val firstNewline = trimmed.indexOf('\n')
    if (firstNewline < 0) return value
    return trimmed.substring(firstNewline + 1, trimmed.length - MARKDOWN_FENCE_LENGTH).trim()
}

private fun JsonElement.containsOversizeString(maxLength: Int): Boolean = when (this) {
    is JsonObject -> values.any { it.containsOversizeString(maxLength) }
    is JsonArray -> any { it.containsOversizeString(maxLength) }
    is JsonPrimitive -> isString && content.length > maxLength
}

private fun JsonObject.requireOnly(allowed: Set<String>) {
    if (!allowed.containsAll(keys)) throw RejectedOperationException()
}

private fun JsonObject.requiredString(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        ?: throw RejectedOperationException()

private fun JsonObject.optionalString(name: String): String? {
    if (name !in this) return null
    return requiredString(name)
}

@Suppress("ThrowsCount")
private fun JsonObject.requiredPositiveId(name: String): Long {
    val primitive = this[name] as? JsonPrimitive ?: throw RejectedOperationException()
    if (primitive.isString || primitive.booleanOrNull != null) throw RejectedOperationException()
    return primitive.longOrNull?.takeIf { it > 0L } ?: throw RejectedOperationException()
}

private fun JsonObject.requiredEventType(name: String): EventType = when (requiredString(name)) {
    "reminder" -> EventType.REMINDER
    "timespan" -> EventType.TIMESPAN
    else -> throw RejectedOperationException()
}

private fun JsonObject.requiredTime(name: String): LocalDateTime = parseTime(requiredString(name))

private fun JsonObject.optionalTime(name: String): LocalDateTime? {
    if (name !in this || this[name] === JsonNull) return null
    return requiredTime(name)
}

@Suppress("ThrowsCount")
private fun JsonObject.optionalNonNegativeInt(name: String): Int? {
    if (name !in this) return null
    val primitive = this[name] as? JsonPrimitive ?: throw RejectedOperationException()
    if (primitive.isString || primitive.booleanOrNull != null) throw RejectedOperationException()
    return primitive.intOrNull?.takeIf { it >= 0 } ?: throw RejectedOperationException()
}

private fun JsonObject.fieldEventType(name: String): FieldUpdate<EventType> =
    if (name in this) FieldUpdate.Set(requiredEventType(name)) else FieldUpdate.Unchanged

private fun JsonObject.fieldTrimmedNonBlankString(name: String): FieldUpdate<String> {
    if (name !in this) return FieldUpdate.Unchanged
    val value = requiredString(name).trim()
    if (value.isEmpty()) throw RejectedOperationException()
    return FieldUpdate.Set(value)
}

private fun JsonObject.fieldString(name: String): FieldUpdate<String> =
    if (name in this) FieldUpdate.Set(requiredString(name)) else FieldUpdate.Unchanged

private fun JsonObject.fieldTime(name: String): FieldUpdate<LocalDateTime> =
    if (name in this) FieldUpdate.Set(requiredTime(name)) else FieldUpdate.Unchanged

private fun JsonObject.fieldNullableTime(name: String): FieldUpdate<LocalDateTime?> = when {
    name !in this -> FieldUpdate.Unchanged
    this[name] === JsonNull -> FieldUpdate.Set(null)
    else -> FieldUpdate.Set(requiredTime(name))
}

private fun JsonObject.fieldNonNegativeInt(name: String): FieldUpdate<Int> = if (name in this) {
    FieldUpdate.Set(requireNotNull(optionalNonNegativeInt(name)))
} else {
    FieldUpdate.Unchanged
}

private fun parseTime(value: String): LocalDateTime = try {
    WallClockCodec.parse(value)
} catch (_: IllegalArgumentException) {
    throw RejectedOperationException()
}

private const val MARKDOWN_FENCE_LENGTH = 3

private fun String.looksLikeOperationResponse(): Boolean {
    val trimmed = trimStart()
    val structured = trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("```")
    return structured && OPERATION_FIELD_MARKER.containsMatchIn(trimmed)
}

private val OPERATION_FIELD_MARKER = Regex("\"(?:operations|action)\"\\s*:")
