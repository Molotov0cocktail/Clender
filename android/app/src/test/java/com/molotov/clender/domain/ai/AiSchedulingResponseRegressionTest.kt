package com.molotov.clender.domain.ai

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSchedulingResponseRegressionTest {
    private val parser = AiResponseParser()

    @Test
    fun proseContainingAnOperationEnvelopeRejectsInsteadOfEchoingAnUnexecutedBatch() {
        listOf(
            "我会为你修改提醒：\n${schedule()}",
            "说明：\n```json\n${schedule()}\n```\n请核对",
            "先处理第一批：${schedule()}\n再处理第二批：${schedule()}",
            "准备修改：${schedule().dropLast(1)}",
            "普通回复中提及 {\"operations\": []}，不执行。"
        ).forEach(::assertRejected)
    }

    @Test
    fun replyContainingAnOperationEnvelopeRejectsTheWholeBatchBeforeAnyWrite() {
        val embedded = JsonPrimitive("这是要执行的内容：${schedule()}")
        val reply = """{"action":"reply","message":$embedded}"""
        assertRejected(reply)
        assertRejected("""{"operations":[{"action":"delete","event_id":1},$reply]}""")
    }

    @Test
    fun replyExplainingAnIndividualActionIsNotRecursivelyExecuted() {
        val message = "Example: {\"action\":\"delete\",\"event_id\":1}"
        val result = parser.parse("""{"action":"reply","message":${JsonPrimitive(message)}}""")
            as AiParseResult.Operations
        assertEquals(listOf(AiOperation.Reply(message)), result.operations)
    }

    @Test
    fun completeTenOperationScheduleParsesEveryOperation() {
        val result = parser.parse(schedule()) as AiParseResult.Operations

        assertEquals(10, result.operations.size)
        assertEquals(9, result.operations.count { it is AiOperation.Add })
        assertEquals(AiOperation.Reply("Schedule proposal"), result.operations.last())
    }

    @Test
    fun missingBatchClosureRejectsInsteadOfDisplayingRawOperations() {
        assertRejected(schedule().dropLast(1))
    }

    @Test
    fun truncatedFinalOperationDoesNotRecoverEarlierCompleteOperations() {
        assertRejected(schedule().substringBefore("Schedule proposal") + "Schedule")
    }

    @Test
    fun malformedOperationArrayAndSingleActionAreRejected() {
        listOf(
            """[{"action":"delete","event_id":3},""",
            """{"action":"delete","event_id":3""",
            """{"operations":[{"action":"reply","message":"a"} {"action":"reply","message":"b"}]}""",
            """{"operations":"""
        ).forEach(::assertRejected)
    }

    @Test
    fun malformedFencedOperationsAreRejectedWithOrWithoutClosingFence() {
        listOf(
            "```json\n${schedule().dropLast(1)}\n```",
            "```json\n${schedule().dropLast(1)}",
            "```\n${schedule().dropLast(1)}\n```"
        ).forEach(::assertRejected)
    }

    @Test
    fun structuredOperationResponseWithTrailingTextIsNotPartiallyExecuted() {
        assertRejected(schedule() + "\nAdditional explanation")
    }

    @Test
    fun ordinaryTextMentioningOperationJsonRemainsAnOrdinaryReply() {
        listOf(
            "I can explain operations and action fields.",
            "Example: {\"action\":\"reply\",\"message\":\"hello\"}",
            "```json\n{broken}\n```",
            "{\"answer\":\"not an operation\"}",
            "[1,2,3]"
        ).forEach { content ->
            assertEquals(AiParseResult.PlainReply(content), parser.parse(content))
        }
    }

    @Test
    fun validOperationShapesStillParseWithoutRepair() {
        listOf(
            schedule(),
            "```json\n${schedule()}\n```",
            """[{"action":"reply","message":"hello"}]""",
            """{"action":"reply","message":"hello"}"""
        ).forEach { content ->
            assertTrue(parser.parse(content) is AiParseResult.Operations)
        }
    }

    @Test
    fun unsafeFinalActionRejectsTheEntireOtherwiseValidSchedule() {
        assertRejected(schedule().replace("\"action\":\"reply\"", "\"action\":\"shell\""))
    }

    private fun assertRejected(content: String) {
        val result = parser.parse(content)
        assertTrue("Expected safe rejection", result is AiParseResult.Rejected)
        val message = (result as AiParseResult.Rejected).userMessage
        assertTrue(message.isNotBlank())
        assertFalse(message.contains("operations"))
        assertFalse(message.contains("Synthetic task"))
    }

    private fun schedule(): String {
        val additions = (1..9).joinToString(",") { index ->
            """{"action":"add","event_type":"reminder","title":"Synthetic task $index",""" +
                """"start_time":"2026-09-08 09:0$index","end_time":null}"""
        }
        return """{"operations":[$additions,{"action":"reply","message":"Schedule proposal"}]}"""
    }
}
