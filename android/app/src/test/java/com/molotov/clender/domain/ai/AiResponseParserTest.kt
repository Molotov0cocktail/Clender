package com.molotov.clender.domain.ai

import com.molotov.clender.core.model.EventType
import com.molotov.clender.domain.event.FieldUpdate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiResponseParserTest {
    private val parser = AiResponseParser(
        limits = AiResponseLimits(
            maxPayloadChars = 32_768,
            maxOperations = 16,
            maxStringChars = 4_096
        )
    )

    @Test
    fun acceptsWrappedMarkdownArrayAndSingleOperationShapes() {
        val wrapped = parser.parse(
            """{"operations":[{"action":"reply","message":"好 🌏"}]}"""
        ) as AiParseResult.Operations
        val fenced = parser.parse(
            """
            |```json
            |[{"action":"delete","event_id":7}]
            |```
            """.trimMargin().trim()
        ) as AiParseResult.Operations
        val single = parser.parse(
            """{"action":"add","event_type":"reminder","title":"事项",""" +
                """"start_time":"2026-08-09 09:30"}"""
        ) as AiParseResult.Operations

        assertEquals(AiOperation.Reply("好 🌏"), wrapped.operations.single())
        assertEquals(AiOperation.Delete(7), fenced.operations.single())
        val add = single.operations.single() as AiOperation.Add
        assertEquals(EventType.REMINDER, add.command.eventType)
        assertEquals(LocalDateTime.of(2026, 8, 9, 9, 30), add.command.startTime)
    }

    @Test
    fun malformedTextAndJsonWithoutActionRemainPlainReplies() {
        listOf(
            "普通回复",
            "```json\n{broken}\n```",
            "{\"answer\":\"not an operation\"}",
            "[1,2,3]"
        ).forEach { content ->
            val result = parser.parse(content)
            assertTrue(result is AiParseResult.PlainReply)
            assertEquals(content, (result as AiParseResult.PlainReply).message)
        }
    }

    @Test
    fun updateCanConvertTypeAndExplicitlySetOrClearEndTime() {
        val toTimespan = parser.parse(
            """{"action":"update","event_id":9,"event_type":"timespan",""" +
                """"end_time":"2026-08-09 11:00"}"""
        ) as AiParseResult.Operations
        val toReminder = parser.parse(
            """{"action":"update","event_id":9,"event_type":"reminder","end_time":null}"""
        ) as AiParseResult.Operations

        val timespanPatch = (toTimespan.operations.single() as AiOperation.Update).patch
        assertEquals(FieldUpdate.Set(EventType.TIMESPAN), timespanPatch.eventType)
        assertEquals(
            FieldUpdate.Set(LocalDateTime.of(2026, 8, 9, 11, 0)),
            timespanPatch.endTime
        )
        val reminderPatch = (toReminder.operations.single() as AiOperation.Update).patch
        assertEquals(FieldUpdate.Set(EventType.REMINDER), reminderPatch.eventType)
        assertEquals(FieldUpdate.Set(null), reminderPatch.endTime)
    }

    @Test
    fun dangerousActionsUnknownFieldsAndInvalidIdsRejectTheWholeResponse() {
        val payloads = listOf(
            """{"action":"shell","command":"ignored"}""",
            """{"action":"open_url","url":"https://example.invalid"}""",
            """{"action":"add","event_type":"reminder","title":"x",""" +
                """"start_time":"2026-08-09 09:00","intent":"ignored"}""",
            """{"action":"delete","event_id":0}""",
            """{"action":"delete","event_id":-1}""",
            """{"action":"delete","event_id":true}""",
            """{"operations":[{"action":"reply","message":"ok"},""" +
                """{"action":"sql","query":"ignored"}]}""",
            """{"operations":[],"extra":1}""",
            """{"operations":{}}""",
            """[{"action":"reply","message":"ok"},1]"""
        )

        payloads.forEach { payload ->
            val result = parser.parse(payload)
            assertTrue("must reject: $payload", result is AiParseResult.Rejected)
            val message = (result as AiParseResult.Rejected).userMessage
            assertTrue(message.isNotBlank())
            assertFalse(message.contains("query", ignoreCase = true))
            assertFalse(message.contains("command", ignoreCase = true))
        }
    }

    @Test
    fun invalidEventFieldsAndBlankReplyAreRejectedBeforeExecution() {
        listOf(
            """{"action":"add","event_type":"reminder","title":" ",""" +
                """"start_time":"2026-08-09 09:00"}""",
            """{"action":"add","event_type":"timespan","title":"x",""" +
                """"start_time":"2026-08-09 11:00","end_time":"2026-08-09 10:00"}""",
            """{"action":"add","event_type":"reminder","title":"x",""" +
                """"start_time":"2026/08/09 09:00"}""",
            """{"action":"add","event_type":"reminder","title":"x",""" +
                """"start_time":"2026-02-30 09:00"}""",
            """{"action":"add","event_type":"reminder","title":"x",""" +
                """"start_time":"2026-08-09 09:00:00"}""",
            """{"action":"add","event_type":"reminder","title":"x",""" +
                """"start_time":"026-08-09 09:00"}""",
            """{"action":"update","event_id":1,"event_type":"timespan"}""",
            """{"action":"update","event_id":1,"event_type":"reminder",""" +
                """"end_time":"2026-08-09 10:00"}""",
            """{"action":"update","event_id":1,""" +
                """"start_time":"2026-08-09 11:00","end_time":"2026-08-09 10:00"}""",
            """{"action":"update","event_id":1}""",
            """{"action":"reply","message":" \t"}"""
        ).forEach { payload ->
            assertTrue(parser.parse(payload) is AiParseResult.Rejected)
        }
    }

    @Test
    fun payloadOperationAndStringLimitsFailClosedWithoutEchoingBody() {
        val tooMany = buildString {
            append("[")
            append((1..17).joinToString(",") { "{\"action\":\"delete\",\"event_id\":$it}" })
            append("]")
        }
        val tooLongString =
            "{\"action\":\"reply\",\"message\":\"${"x".repeat(4_097)}\"}"
        val tooLarge = "x".repeat(32_769)

        listOf(tooMany, tooLongString, tooLarge).forEach { payload ->
            val result = parser.parse(payload)
            assertTrue(result is AiParseResult.Rejected)
            assertFalse((result as AiParseResult.Rejected).userMessage.contains("x".repeat(32)))
        }
    }
}
