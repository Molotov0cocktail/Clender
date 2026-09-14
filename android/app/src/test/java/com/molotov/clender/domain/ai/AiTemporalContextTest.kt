package com.molotov.clender.domain.ai

import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTemporalContextTest {
    private val budgeter = AiMessageBudgeter()

    @Test
    fun crossDayHistoryKeepsUtcSendTimesAndOnlyLatestUserIsCurrent() {
        val history = listOf(
            message(1, MessageRole.USER, "Tomorrow review the draft", "2026-12-30T16:30:00Z"),
            message(2, MessageRole.ASSISTANT, "Draft review proposed", "2026-12-30T16:31:00Z"),
            message(3, MessageRole.THINK, "private reasoning", "2026-12-30T16:31:01Z"),
            message(4, MessageRole.USER, "Today review the draft", "2026-12-31T16:30:00Z")
        )
        val original = history.toList()
        val result = budgeter.build(input(history))
        assertEquals(listOf("system", "user", "assistant", "user"), result.messages.map { it.role })
        assertTrue(result.messages[1].content.contains("sent_at_utc=2026-12-30T16:30:00Z"))
        assertTrue(result.messages[1].content.contains("historical"))
        assertTrue(result.messages[2].content.contains("sent_at_utc=2026-12-30T16:31:00Z"))
        assertTrue(result.messages.last().content.contains("current user"))
        assertTrue(result.messages.last().content.contains("sent_at_utc=2026-12-31T16:30:00Z"))
        assertTrue(result.messages.last().content.endsWith(history.last().content))
        assertFalse(result.messages.any { it.content.contains("private reasoning") })
        assertEquals(original, history)
    }

    @Test
    fun sameTextAtDifferentTimesRemainsDistinguishable() {
        val history = listOf(
            message(1, MessageRole.USER, "Today", "2026-12-30T16:30:00Z"),
            message(2, MessageRole.USER, "Today", "2026-12-31T16:30:00Z")
        )
        val result = budgeter.build(input(history))
        assertTrue(result.messages[1].content != result.messages[2].content)
        assertTrue(result.messages.drop(1).all { it.content.endsWith("Today") })
    }

    @Test
    fun constrainedBudgetRetainsCurrentMetadataClockAndUnicodeTail() {
        val latest = message(
            1,
            MessageRole.USER,
            "🌏".repeat(2_000) + "latest-tail",
            "2026-12-31T16:30:00Z"
        )
        val result = budgeter.build(input(listOf(latest)).copy(contextWindow = 500))
        val content = result.messages.last().content
        assertTrue(content.contains("current user"))
        assertTrue(content.contains("sent_at_utc=2026-12-31T16:30:00Z"))
        assertTrue(content.contains("[Earlier message text omitted]"))
        assertTrue(content.endsWith("latest-tail"))
        val system = result.messages.first().content
        assertTrue(system.contains("Current local date/time: 2027-01-01 00:30"))
        assertEquals(budgeter.countTokens(result.messages), result.inputTokenEstimate)
        assertTrue(result.inputTokenEstimate <= 350)
        assertFalse(content.any { it.isLowSurrogate() && content.indexOf(it) == 0 })
    }

    @Test
    fun currentContextCarriesZoneAndOffsetForHistoricalUtcInterpretation() = runBlocking {
        val provider = AiContextProvider(
            Clock.fixed(Instant.parse("2026-12-31T16:30:00Z"), ZoneOffset.UTC),
            VisibleScheduleSource { emptyList() },
            { ZoneId.of("Asia/Shanghai") }
        )
        val header = provider.build().substringBefore('\n')
        assertTrue(header.contains("2027-01-01 00:30"))
        assertTrue(header.contains("zone: Asia/Shanghai"))
        assertTrue(header.contains("UTC offset: +08:00"))
    }

    @Test
    fun fixedContractSeparatesCurrentClockHistoricalRequestsAndStoredScheduleValues() {
        assertTrue(DEFAULT_AI_SYSTEM_CONTRACT.contains("每次请求重新读取"))
        assertTrue(DEFAULT_AI_SYSTEM_CONTRACT.contains("历史消息中的“今天”"))
        assertTrue(DEFAULT_AI_SYSTEM_CONTRACT.contains("不是原始值"))
        assertTrue(DEFAULT_AI_SYSTEM_CONTRACT.contains("不能据此断言设备时钟错误"))
    }

    @Test
    fun budgetTooSmallForMessageMetadataAndBodyFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            val latest = message(1, MessageRole.USER, "hello", "2026-12-31T16:30:00Z")
            budgeter.build(
                input(listOf(latest)).copy(
                    scheduleContext = "",
                    contextWindow = 70,
                    maxOutputTokens = 20
                )
            )
        }
    }

    private fun input(history: List<Message>) = AiBudgetInput(
        mandatorySystemContract = "contract",
        systemPrompt = "",
        personality = "",
        scheduleContext = "Current local date/time: 2027-01-01 00:30; weekday: Friday.\n" +
            "Visible schedules:\n(none)",
        history = history,
        contextWindow = 4_000,
        maxOutputTokens = 100
    )

    private fun message(id: Long, role: MessageRole, content: String, time: String) =
        Message(id, "synthetic", role, content, Instant.parse(time))
}
