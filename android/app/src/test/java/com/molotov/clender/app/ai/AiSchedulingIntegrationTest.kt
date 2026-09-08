package com.molotov.clender.app.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.MessageRole
import com.molotov.clender.data.local.ClenderDatabase
import com.molotov.clender.data.local.RoomConversationRepository
import com.molotov.clender.data.local.RoomEventRepository
import com.molotov.clender.data.network.ai.AiClient
import com.molotov.clender.data.network.ai.AiCompletion
import com.molotov.clender.data.network.ai.AiCompletionUsage
import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.data.settings.ThinkingEffort
import com.molotov.clender.domain.ai.AiContextProvider
import com.molotov.clender.domain.ai.AiMessageBudgeter
import com.molotov.clender.domain.ai.AiOperationExecutor
import com.molotov.clender.domain.ai.AiRequestMessage
import com.molotov.clender.domain.ai.AiResponseParser
import com.molotov.clender.domain.ai.VisibleScheduleSource
import com.molotov.clender.domain.event.EventService
import com.molotov.clender.domain.event.ScheduleMutation
import com.molotov.clender.domain.event.ScheduleMutationSink
import com.molotov.clender.domain.event.SyncUidGenerator
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AiSchedulingIntegrationTest {
    private lateinit var database: ClenderDatabase
    private lateinit var conversations: RoomConversationRepository
    private lateinit var events: EventService
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.Default)
    private val mutations = mutableListOf<ScheduleMutation>()
    private val clock = Clock.fixed(Instant.parse("2026-09-07T04:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ClenderDatabase::class.java
        ).allowMainThreadQueries().build()
        conversations = RoomConversationRepository(database)
        events = EventService(
            RoomEventRepository(database),
            clock,
            SyncUidGenerator { UUID.randomUUID().toString().replace("-", "") },
            ScheduleMutationSink { mutations += it }
        )
        conversations.create(Conversation("schedule-test", "Synthetic", clock.instant(), 0))
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        owner.cancelAndJoin()
        database.close()
    }

    @Test
    fun aiReminderPolicyIsPersistedAndCanBeDisabledThroughSameValidatedWritePath() = runBlocking {
        val addWithPolicy = add(1).dropLast(1) +
            ""","notification_enabled":false,"alarm_enabled":true,"timer_minutes":15}"""
        complete("""{"operations":[$addWithPolicy]}""")
        val created = events.observeDate(day).first().single()
        assertFalse(created.notificationEnabled)
        assertTrue(created.alarmEnabled)
        assertEquals(15, created.timerMinutes)
        complete(
            """{"action":"update","event_id":${created.id},"notification_enabled":true,
                |"alarm_enabled":false,"timer_minutes":0}
            """.trimMargin()
        )
        val updated = events.observeDate(day).first().single()
        assertTrue(updated.notificationEnabled)
        assertFalse(updated.alarmEnabled)
        assertEquals(0, updated.timerMinutes)
    }

    @Test
    fun repeatedLegacyReceiptIsReplacedBySingleChineseActualReceipt() = runBlocking {
        val replies = complete(
            "No schedule changes were made. 本轮未修改日程。\n\nAssistant reply / 模型回复：\n" +
                "4 schedule operation(s) completed. 已实际完成 4 项日程操作。\n\n" +
                "Assistant reply / 模型回复：\n请核对日期"
        )
        assertEquals(listOf("本轮未修改日程。\n\n模型回复：\n请核对日期"), replies)
        assertTrue(events.observeDate(day).first().isEmpty())
    }

    @Test
    fun t66ReplyOnlyHasExplicitNoWriteReceipt() = runBlocking {
        val replies = complete("""{"operations":[$REPLY]}""")
        assertTrue(replies.first().startsWith("本轮未修改日程。"))
        assertEquals(0, events.observeDate(day).first().size)
        assertTrue(mutations.isEmpty())
    }

    @Test
    fun t66PlainClaimHasExplicitNoWriteReceipt() = runBlocking {
        val replies = complete("All scheduled")
        assertTrue(replies.first().startsWith("本轮未修改日程。"))
        assertEquals(0, events.observeDate(day).first().size)
        assertTrue(mutations.isEmpty())
    }

    @Test
    fun t66SuccessReceiptComesFromRoomWrites() = runBlocking {
        val replies = complete("""{"operations":[${add(1)},$REPLY]}""")
        assertTrue(replies.first().startsWith("已实际完成 1 项日程操作。"))
        assertEquals(1, events.observeDate(day).first().size)
    }

    @Test
    fun nineEventsAndReplyPersistOnceAndRefreshMonthCounts() = runBlocking {
        val operations = (1..9).joinToString(",") { add(it) }
        val reply = complete("""{"operations":[$operations,$REPLY]}""")

        assertTrue(reply.single().startsWith("已实际完成 9 项日程操作。"))
        assertTrue(reply.single().endsWith("All scheduled"))
        assertEquals(9, events.observeDate(day).first().size)
        assertEquals(9, events.observeMonthCounts(YearMonth.from(day)).first()[day])
        assertEquals(9, (mutations.single() as ScheduleMutation.Batch).mutations.size)
        assertEquals(15, conversations.findConversation("schedule-test")?.tokenCount)
    }

    @Test
    fun truncatedOperationEnvelopeNeverBecomesAnAssistantSuccessMessage() = runBlocking {
        val content = """{"operations":[${add(1)},$REPLY]"""
        val replies = complete(
            content,
            AiCoordinatorState.Failed(AiCoordinatorError.INVALID_RESPONSE)
        )

        assertEquals(0, events.observeDate(day).first().size)
        assertTrue(mutations.isEmpty())
        assertFalse(replies.any { it.contains("All scheduled") || it.contains("operations") })
        assertTrue(replies.isNotEmpty())
    }

    @Test
    fun invalidOperationRejectsWholeEnvelopeBeforeRoomWrite() = runBlocking {
        val replies = complete(
            """{"operations":[${add(1)},{"action":"shell"},$REPLY]}""",
            AiCoordinatorState.Failed(AiCoordinatorError.INVALID_RESPONSE)
        )

        assertEquals(0, events.observeDate(day).first().size)
        assertTrue(mutations.isEmpty())
        assertFalse(replies.any { it.contains("All scheduled") })
    }

    @Test
    fun failedUpdateCannotRepeatTheModelsSuccessClaim() = runBlocking {
        val replies = complete("""{"operations":[$MISSING_UPDATE,$REPLY]}""")

        assertEquals(0, events.observeDate(day).first().size)
        assertTrue(mutations.isEmpty())
        assertFalse(replies.any { it.contains("All scheduled") })
        assertEquals(
            listOf(
                "已实际完成 0 项日程操作，1 项未能执行。请先核对日历再重试。"
            ),
            replies
        )
    }

    @Test
    fun partialFailureKeepsCommittedEventAndReportsFailureInsteadOfModelClaim() = runBlocking {
        val replies = complete("""{"operations":[${add(1)},$MISSING_UPDATE,$REPLY]}""")

        assertEquals(1, events.observeDate(day).first().size)
        assertEquals(1, (mutations.single() as ScheduleMutation.Batch).mutations.size)
        assertFalse(replies.any { it.contains("All scheduled") })
        assertEquals(
            listOf(
                "已实际完成 1 项日程操作，1 项未能执行。请先核对日历再重试。"
            ),
            replies
        )
    }

    @Test
    fun partialFailureWithoutModelReplyMustNotClaimAllOperationsCompleted() = runBlocking {
        val replies = complete("""{"operations":[${add(1)},$MISSING_UPDATE]}""")

        assertEquals(1, events.observeDate(day).first().size)
        assertFalse(replies.contains("Schedule operations completed."))
        assertTrue(replies.any { it.contains("未能执行") })
    }

    private suspend fun complete(
        content: String,
        expectedState: AiCoordinatorState = AiCoordinatorState.Idle
    ): List<String> {
        val client = SchedulingCompletionClient(content)
        val coordinator = AiCoordinator(
            scope,
            AiCoordinatorDependencies(
                client,
                conversations,
                AiMessageBudgeter(),
                AiResponseParser(),
                AiOperationExecutor(events),
                clock,
                AiContextProvider(clock, VisibleScheduleSource { emptyList() }) { clock.zone }
            )
        )
        coordinator.onAppForegrounded()
        val key = CharArray(8) { 'x' }
        assertTrue(coordinator.submit("schedule-test", "Synthetic request", settings(), key))
        withTimeout(10_000) { coordinator.state.first { it !is AiCoordinatorState.Working } }
        assertEquals(expectedState, coordinator.state.value)
        assertTrue(key.all { it == '\u0000' })
        assertEquals(1, client.calls)
        return conversations.observeMessages("schedule-test").first()
            .filter { it.role == MessageRole.ASSISTANT }.map { it.content }
    }

    private fun settings() = AiSettings(
        "https://example.invalid",
        "synthetic",
        0.7,
        512,
        8_192,
        false,
        ThinkingEffort.HIGH,
        "",
        ""
    )

    private fun add(index: Int) =
        """{"action":"add","event_type":"reminder","title":"Synthetic $index",""" +
            """"start_time":"2026-09-07 13:00","end_time":null}"""

    private companion object {
        const val REPLY = """{"action":"reply","message":"All scheduled"}"""
        val day: LocalDate = LocalDate.of(2026, 9, 7)
        const val MISSING_UPDATE = """{"action":"update","event_id":9999,"title":"Missing"}"""
    }
}

private class SchedulingCompletionClient(private val content: String) : AiClient {
    var calls = 0

    override suspend fun fetchModels(settings: AiSettings, apiKey: CharArray): List<String> =
        error("Not used")

    override suspend fun complete(
        settings: AiSettings,
        apiKey: CharArray,
        messages: List<AiRequestMessage>
    ): AiCompletion {
        calls++
        apiKey.fill('\u0000')
        return AiCompletion(content, "", AiCompletionUsage(10, 5, 15))
    }

    override fun cancelInFlight() = Unit
}
