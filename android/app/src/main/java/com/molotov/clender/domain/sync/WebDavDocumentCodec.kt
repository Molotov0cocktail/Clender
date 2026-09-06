package com.molotov.clender.domain.sync

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.UtcInstantCodec
import com.molotov.clender.core.model.WallClockCodec
import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class WebDavDocumentException(message: String) :
    IllegalArgumentException(message),
    BoundedSyncFailure {
    override val syncFailureKind: SyncFailureKind = SyncFailureKind.DOCUMENT
    override val statusCode: Int? = null
}

class WebDavDocumentCodec {
    fun decode(payload: ByteArray): List<Event> {
        document(payload.size <= MAX_DOCUMENT_BYTES) { "WebDAV document exceeds the size limit" }
        val source = decodeWebDavUtf8(payload)
        val root = parseWebDavJson(source).requireObject("WebDAV document root must be an object")
        root.requireExactFields(ROOT_FIELDS, "WebDAV document root fields are invalid")
        document(root.getValue("schema_version").requireExactInteger() == SCHEMA_VERSION) {
            "Unsupported WebDAV schema version"
        }
        val records = root.getValue("events") as? JsonArray
            ?: throw WebDavDocumentException("WebDAV events must be an array")
        document(records.size <= MAX_EVENT_RECORDS) { "WebDAV document has too many events" }
        return normalizeRecords(records.map(::decodeRecord))
    }

    fun encode(events: List<Event>): ByteArray {
        val normalized = normalizeRecords(events)
        val output = buildString {
            append("{\"events\":[")
            normalized.forEachIndexed { index, event ->
                if (index > 0) append(',')
                append(canonicalRecord(event))
            }
            append("],\"schema_version\":1}")
        }.toByteArray(StandardCharsets.UTF_8)
        document(output.size <= MAX_DOCUMENT_BYTES) { "WebDAV document exceeds the size limit" }
        return output
    }

    internal fun normalizeRecords(events: List<Event>): List<Event> {
        document(events.size <= MAX_EVENT_RECORDS) { "WebDAV document has too many events" }
        val seen = HashSet<String>(events.size)
        return events.map(::normalizeEvent)
            .onEach { event ->
                document(seen.add(event.syncUid)) { "WebDAV document contains duplicate sync UIDs" }
            }
            .sortedBy(Event::syncUid)
    }

    internal fun canonicalRecord(event: Event): String {
        val normalized = normalizeEvent(event)
        return buildString {
            append('{')
            append("\"created_at\":")
            appendJsonString(UtcInstantCodec.format(normalized.createdAt))
            append(",\"deleted_at\":")
            appendNullableInstant(normalized.deletedAt)
            append(",\"description\":")
            appendJsonString(normalized.description)
            append(",\"end_time\":")
            if (normalized.endTime == null) {
                append("null")
            } else {
                appendJsonString(WallClockCodec.format(normalized.endTime))
            }
            append(",\"estimated_duration\":")
            append(normalized.estimatedDurationMinutes)
            append(",\"event_type\":")
            appendJsonString(normalized.eventType.wireValue)
            append(",\"start_time\":")
            appendJsonString(WallClockCodec.format(normalized.startTime))
            append(",\"sync_uid\":")
            appendJsonString(normalized.syncUid)
            append(",\"title\":")
            appendJsonString(normalized.title)
            append(",\"updated_at\":")
            appendJsonString(UtcInstantCodec.format(normalized.updatedAt))
            append('}')
        }
    }

    private fun decodeRecord(element: JsonElement): Event {
        val record = element.requireObject("WebDAV event must be an object")
        record.requireExactFields(EVENT_FIELDS, "WebDAV event fields are invalid")
        val eventType = try {
            EventType.fromWire(record.getValue("event_type").requireString("event_type"))
        } catch (_: IllegalArgumentException) {
            throw WebDavDocumentException("WebDAV event type is invalid")
        }
        val title = record.getValue("title").requireString("title")
        val description = record.getValue("description").requireString("description")
        val startTime = parseWallClock(record.getValue("start_time"), "start_time")
        val endTime = record.getValue("end_time").nullableString("end_time")?.let { value ->
            parseWallClock(value, "end_time")
        }
        val duration = record.getValue("estimated_duration").requireNonNegativeInt()
        val createdAt = parseDesktopUtc(record.getValue("created_at"), "created_at")
        val updatedAt = parseDesktopUtc(record.getValue("updated_at"), "updated_at")
        val deletedAt = record.getValue("deleted_at").nullableString("deleted_at")?.let { value ->
            parseDesktopUtc(value, "deleted_at")
        }
        return normalizeEvent(
            Event(
                id = 0L,
                eventType = eventType,
                title = title,
                startTime = startTime,
                endTime = endTime,
                description = description,
                estimatedDurationMinutes = duration,
                createdAt = createdAt,
                syncUid = record.getValue("sync_uid").requireString("sync_uid"),
                updatedAt = updatedAt,
                deletedAt = deletedAt
            )
        )
    }

    private fun normalizeEvent(event: Event): Event {
        val title = event.title.trim()
        document(title.isNotEmpty()) { "WebDAV event title is invalid" }
        document(event.estimatedDurationMinutes >= 0) { "WebDAV event duration is invalid" }
        document(SYNC_UID.matches(event.syncUid)) { "WebDAV event sync UID is invalid" }
        requireUnicodeScalars(title)
        requireUnicodeScalars(event.description)
        wrapCodecFailure("WebDAV start_time is invalid") {
            WallClockCodec.format(event.startTime)
        }
        event.endTime?.let { end ->
            wrapCodecFailure("WebDAV end_time is invalid") { WallClockCodec.format(end) }
        }
        document(event.createdAt.hasWebDavMicrosecondPrecision()) { "WebDAV created_at is invalid" }
        document(event.updatedAt.hasWebDavMicrosecondPrecision()) { "WebDAV updated_at is invalid" }
        document(event.deletedAt?.hasWebDavMicrosecondPrecision() != false) {
            "WebDAV deleted_at is invalid"
        }
        wrapCodecFailure("WebDAV created_at is invalid") {
            UtcInstantCodec.format(event.createdAt)
        }
        wrapCodecFailure("WebDAV updated_at is invalid") {
            UtcInstantCodec.format(event.updatedAt)
        }
        event.deletedAt?.let { deleted ->
            wrapCodecFailure("WebDAV deleted_at is invalid") {
                UtcInstantCodec.format(deleted)
            }
        }
        document(!event.updatedAt.isBefore(event.createdAt)) {
            "WebDAV updated_at precedes created_at"
        }
        document(event.deletedAt == null || !event.deletedAt.isAfter(event.updatedAt)) {
            "WebDAV deleted_at follows updated_at"
        }
        when (event.eventType) {
            EventType.REMINDER -> document(event.endTime == null) {
                "WebDAV reminder must not have end_time"
            }

            EventType.TIMESPAN -> document(
                event.endTime != null && event.endTime.isAfter(event.startTime)
            ) {
                "WebDAV timespan end_time must follow start_time"
            }
        }
        return event.copy(title = title)
    }

    private fun parseDesktopUtc(element: JsonElement, field: String): Instant =
        parseDesktopUtc(element.requireString(field), field)

    private fun parseDesktopUtc(value: String, field: String): Instant {
        val match = DESKTOP_UTC.matchEntire(value)
            ?: throw WebDavDocumentException("WebDAV $field is invalid")
        val fraction = match.groups[1]?.value.orEmpty().padEnd(MICROSECOND_DIGITS, '0')
        val canonical = value.substringBefore('.')
            .removeSuffix("Z") + "." + fraction + "Z"
        return try {
            UtcInstantCodec.parse(canonical)
        } catch (_: IllegalArgumentException) {
            throw WebDavDocumentException("WebDAV $field is invalid")
        }
    }

    private fun parseWallClock(element: JsonElement, field: String) =
        parseWallClock(element.requireString(field), field)

    private fun parseWallClock(value: String, field: String) = try {
        WallClockCodec.parse(value)
    } catch (_: IllegalArgumentException) {
        throw WebDavDocumentException("WebDAV $field is invalid")
    }

    companion object {
        const val MAX_DOCUMENT_BYTES: Int = 5 * 1024 * 1024
        const val MAX_EVENT_RECORDS: Int = 100_000
        private const val SCHEMA_VERSION = 1
        private const val MICROSECOND_DIGITS = 6
        private val SYNC_UID = Regex("[0-9a-f]{32}")
        private val DESKTOP_UTC = Regex(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.([0-9]{1,6}))?Z"
        )
        private val ROOT_FIELDS = setOf("schema_version", "events")
        private val EVENT_FIELDS = setOf(
            "sync_uid",
            "event_type",
            "title",
            "start_time",
            "end_time",
            "description",
            "estimated_duration",
            "created_at",
            "updated_at",
            "deleted_at"
        )
    }
}

private fun JsonElement.requireObject(message: String): JsonObject =
    this as? JsonObject ?: throw WebDavDocumentException(message)

private fun JsonObject.requireExactFields(expected: Set<String>, message: String) {
    document(keys == expected) { message }
}

private fun JsonElement.requireString(field: String): String {
    val primitive = this as? JsonPrimitive
    document(primitive != null && primitive.isString) { "WebDAV $field must be a string" }
    val value = checkNotNull(primitive).content
    requireUnicodeScalars(value)
    return value
}

private fun JsonElement.nullableString(field: String): String? =
    if (this === JsonNull) null else requireString(field)

private fun JsonElement.requireExactInteger(): Int {
    val primitive = this as? JsonPrimitive
    document(primitive != null && !primitive.isString && INTEGER.matches(primitive.content)) {
        "WebDAV schema version must be an integer"
    }
    return primitive?.content?.toIntOrNull()
        ?: throw WebDavDocumentException("WebDAV schema version is invalid")
}

private fun JsonElement.requireNonNegativeInt(): Int {
    val primitive = this as? JsonPrimitive
    document(
        primitive != null &&
            !primitive.isString &&
            NON_NEGATIVE_INTEGER.matches(primitive.content)
    ) {
        "WebDAV estimated_duration must be a non-negative integer"
    }
    return primitive?.content?.toIntOrNull()
        ?: throw WebDavDocumentException("WebDAV estimated_duration is outside the supported range")
}

private fun StringBuilder.appendNullableInstant(value: Instant?) {
    if (value == null) append("null") else appendJsonString(UtcInstantCodec.format(value))
}

private fun StringBuilder.appendJsonString(value: String) {
    requireUnicodeScalars(value)
    append('"')
    var index = 0
    while (index < value.length) {
        val character = value[index]
        when (character) {
            '"' -> append("\\\"")

            '\\' -> append("\\\\")

            '\b' -> append("\\b")

            '\u000C' -> append("\\f")

            '\n' -> append("\\n")

            '\r' -> append("\\r")

            '\t' -> append("\\t")

            else -> when {
                character.code < CONTROL_CHARACTER_LIMIT -> {
                    append("\\u")
                    append(
                        character.code.toString(HEX_RADIX).padStart(JSON_ESCAPE_HEX_DIGITS, '0')
                    )
                }

                Character.isHighSurrogate(character) -> {
                    append(character)
                    append(value[index + 1])
                    index += 1
                }

                else -> append(character)
            }
        }
        index += 1
    }
    append('"')
}

private inline fun document(condition: Boolean, message: () -> String) {
    if (!condition) throw WebDavDocumentException(message())
}

private inline fun <T> wrapCodecFailure(message: String, block: () -> T): T = try {
    block()
} catch (_: DateTimeException) {
    throw WebDavDocumentException(message)
} catch (_: IllegalArgumentException) {
    throw WebDavDocumentException(message)
}

private val INTEGER = Regex("-?(?:0|[1-9][0-9]*)")
private val NON_NEGATIVE_INTEGER = Regex("0|[1-9][0-9]*")
private const val CONTROL_CHARACTER_LIMIT = 0x20
private const val HEX_RADIX = 16
private const val JSON_ESCAPE_HEX_DIGITS = 4
