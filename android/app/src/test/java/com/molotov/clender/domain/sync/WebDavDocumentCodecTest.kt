package com.molotov.clender.domain.sync

import com.molotov.clender.core.model.EventType
import java.time.LocalDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavDocumentCodecTest {
    private val codec = WebDavDocumentCodec()

    @Test
    fun desktopSchemaRoundTripsWithStableUidOrderingAndNoLocalIntegerId() {
        val laterUid = syncEvent(uid = syncUid(2), title = "后")
        val earlierUid = syncEvent(uid = syncUid(1), title = "前")

        val payload = codec.encode(listOf(laterUid, earlierUid))
        val decoded = codec.decode(payload)

        assertEquals(listOf(syncUid(1), syncUid(2)), decoded.map { it.syncUid })
        assertEquals(listOf(0L, 0L), decoded.map { it.id })
        assertArrayEquals(payload, codec.encode(decoded))
        assertEquals(1, Regex("\\\"schema_version\\\":1").findAll(payload.decodeToString()).count())
    }

    @Test
    fun encodingExactlyMatchesPythonCanonicalUnicodeAndEscapingGoldenVector() {
        val expected = checkNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "webdav/python-canonical-document.json"
            )
        ).bufferedReader(Charsets.UTF_8).use { it.readText().trimEnd('\r', '\n') }
        val record = syncEvent(
            uid = "0123456789abcdef0123456789abcdef",
            title = "中文😀",
            description = "quote:\" slash:\\ newline:\n",
            estimatedDurationMinutes = 481
        )

        assertEquals(expected, codec.encode(listOf(record)).decodeToString())
        assertEquals(listOf(record), codec.decode(expected.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun acceptsDesktopDurationBeyondUiShortcutAndStrictCrossDayTimespan() {
        val record = jsonRecord(
            eventTypeJson = "\"timespan\"",
            startTimeJson = "\"2026-08-03 23:59\"",
            endTimeJson = "\"2026-08-04 00:01\"",
            durationJson = "481"
        )

        val decoded = codec.decode(jsonDocument(record)).single()

        assertEquals(EventType.TIMESPAN, decoded.eventType)
        assertEquals(LocalDateTime.of(2026, 8, 4, 0, 1), decoded.endTime)
        assertEquals(481, decoded.estimatedDurationMinutes)
    }

    @Test
    fun acceptsAndNormalizesDesktopUtcVariantsAndHistoricalTombstoneTime() {
        val record = jsonRecord(
            createdAtJson = "\"2026-08-03T00:00:00Z\"",
            updatedAtJson = "\"2026-08-03T02:00:00.1Z\"",
            deletedAtJson = "\"2026-08-03T01:00:00.01Z\""
        )

        val decoded = codec.decode(jsonDocument(record)).single()
        val encoded = codec.encode(listOf(decoded)).decodeToString()

        assertEquals("2026-08-03T00:00:00Z", decoded.createdAt.toString())
        assertEquals("2026-08-03T02:00:00.100Z", decoded.updatedAt.toString())
        assertEquals("2026-08-03T01:00:00.010Z", decoded.deletedAt.toString())
        assertTrue(encoded.contains("2026-08-03T00:00:00.000000Z"))
        assertTrue(encoded.contains("2026-08-03T02:00:00.100000Z"))
        assertTrue(encoded.contains("2026-08-03T01:00:00.010000Z"))
    }

    @Test
    fun rejectsUnknownMissingOrDuplicateSchemaFieldsWithoutPartialResult() {
        val valid = jsonRecord()
        val cases = listOf(
            """{"schema_version":1,"events":[],"unknown":true}""".toByteArray(),
            """{"schema_version":1}""".toByteArray(),
            """{"schema_version":2,"events":[]}""".toByteArray(),
            jsonDocument(valid.replace("\"title\":\"事项\",", "")),
            jsonDocument(jsonRecord(extraField = ",\"unknown\":true")),
            jsonDocument(valid, valid)
        )

        cases.forEach { payload ->
            assertThrows(WebDavDocumentException::class.java) { codec.decode(payload) }
        }
    }

    @Test
    fun rejectsMalformedRecordRulesAndPythonBooleanAsInteger() {
        val cases = listOf(
            jsonRecord(uid = "A".repeat(32)),
            jsonRecord(eventTypeJson = "\"unknown\""),
            jsonRecord(titleJson = "\"   \""),
            jsonRecord(startTimeJson = "\"2026-02-30 09:00\""),
            jsonRecord(endTimeJson = "\"2026-08-03 10:00\""),
            jsonRecord(
                eventTypeJson = "\"timespan\"",
                endTimeJson = "\"2026-08-03 09:00\""
            ),
            jsonRecord(durationJson = "-1"),
            jsonRecord(durationJson = "true"),
            jsonRecord(durationJson = "1.0"),
            jsonRecord(updatedAtJson = "\"2026-08-02T23:59:59.999999Z\""),
            jsonRecord(deletedAtJson = "\"2026-08-04T00:00:00.000000Z\"")
        )

        cases.forEach { record ->
            assertThrows(WebDavDocumentException::class.java) {
                codec.decode(jsonDocument(record))
            }
        }
    }

    @Test
    fun rejectsInvalidUtf8AndUnpairedJsonSurrogates() {
        val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)
        val unpairedSurrogate = jsonDocument(jsonRecord(titleJson = "\"\\ud800\""))

        assertThrows(WebDavDocumentException::class.java) { codec.decode(invalidUtf8) }
        assertThrows(WebDavDocumentException::class.java) { codec.decode(unpairedSurrogate) }
        assertThrows(WebDavDocumentException::class.java) {
            codec.encode(listOf(syncEvent(title = "\uD800")))
        }
    }

    @Test
    fun malformedRemoteValuesNeverAppearInPublicExceptionOrCauseChain() {
        val jsonCanary = "PRIVATE_REMOTE_BODY_CANARY"
        val badTimestamp = "2026-02-30T12:34:56.000000Z"
        val malformedJson = "{\"schema_version\":1,\"events\":[\"$jsonCanary\""
            .toByteArray(Charsets.UTF_8)
        val malformedError = assertThrows(WebDavDocumentException::class.java) {
            codec.decode(malformedJson)
        }
        val timestampError = assertThrows(WebDavDocumentException::class.java) {
            codec.decode(
                jsonDocument(jsonRecord(createdAtJson = "\"$badTimestamp\""))
            )
        }

        listOf(malformedError to jsonCanary, timestampError to badTimestamp)
            .forEach { (error, canary) ->
                val exposed = generateSequence<Throwable>(error) { it.cause }
                    .joinToString("\n") { throwable ->
                        "${throwable.message}\n${throwable.stackTraceToString()}"
                    }
                assertFalse(exposed.contains(canary))
            }
    }

    @Test
    fun enforcesFiveMebibyteAndHundredThousandRecordLimitsBeforeApplication() {
        assertThrows(WebDavDocumentException::class.java) {
            codec.decode(ByteArray(WebDavDocumentCodec.MAX_DOCUMENT_BYTES + 1))
        }
        val tooManyMinimalRecords = buildString {
            append("{\"schema_version\":1,\"events\":[")
            repeat(WebDavDocumentCodec.MAX_EVENT_RECORDS + 1) { index ->
                if (index > 0) append(',')
                append("{}")
            }
            append("]}")
        }.toByteArray()
        assertThrows(WebDavDocumentException::class.java) {
            codec.decode(tooManyMinimalRecords)
        }
        assertThrows(WebDavDocumentException::class.java) {
            codec.encode(
                listOf(
                    syncEvent(description = "x".repeat(WebDavDocumentCodec.MAX_DOCUMENT_BYTES))
                )
            )
        }
    }
}
