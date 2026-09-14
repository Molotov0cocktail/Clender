package com.molotov.clender.live

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.app.ai.AiCoordinator
import com.molotov.clender.app.ai.AiCoordinatorDependencies
import com.molotov.clender.app.ai.presentAssistantMessage
import com.molotov.clender.app.ai.AiCoordinatorState
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.MessageRole
import com.molotov.clender.data.local.ClenderDatabase
import com.molotov.clender.data.local.RoomConversationRepository
import com.molotov.clender.data.local.RoomEventRepository
import com.molotov.clender.data.network.ai.AiClient
import com.molotov.clender.data.network.ai.AiCompletion
import com.molotov.clender.data.network.ai.OkHttpAiClient
import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.data.settings.ThinkingEffort
import com.molotov.clender.domain.ai.AiContextProvider
import com.molotov.clender.domain.ai.AiMessageBudgeter
import com.molotov.clender.domain.ai.AiOperation
import com.molotov.clender.domain.ai.AiOperationExecutor
import com.molotov.clender.domain.ai.AiRequestMessage
import com.molotov.clender.domain.ai.AiResponseParser
import com.molotov.clender.domain.ai.AiParseResult
import com.molotov.clender.core.model.WallClockCodec
import com.molotov.clender.domain.ai.VisibleScheduleSource
import com.molotov.clender.domain.event.EventService
import com.molotov.clender.domain.event.ScheduleMutationSink
import com.molotov.clender.domain.event.SyncUidGenerator
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic data only; no credentials, request headers or provider text are logged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiProviderLiveTest {
    @Test
    fun realProviderCreatesUpdatesAndDeletesThroughProductionCoordinatorAndRoom() = runBlocking {
        val settings = AiSettings(
            endpoint = requiredEnvironment("CLENDER_LIVE_AI_URL"),
            model = requiredEnvironment("CLENDER_LIVE_AI_MODEL"),
            temperature = 0.2,
            maxOutputTokens = 8_192,
            contextWindow = 32_768,
            thinkingEnabled = true,
            thinkingEffort = ThinkingEffort.HIGH,
            systemPrompt = "",
            personality = ""
        )
        val key = requiredEnvironment("CLENDER_LIVE_AI_KEY").toCharArray()
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val audit = LiveHttpAudit()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            audit.record(response.code, response.peekBody(1_048_576).string(), response.header("Retry-After"))
            response
        }.build()
        val client = WipeCheckingClient(OkHttpAiClient(http), audit)
        var openedDatabase: ClenderDatabase? = null
        try {
            val database = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                ClenderDatabase::class.java
            ).allowMainThreadQueries().build().also { openedDatabase = it }
            val clock = Clock.system(ZoneId.of("Asia/Shanghai"))
            val conversations = RoomConversationRepository(database)
            val events = EventService(
                RoomEventRepository(database), clock,
                SyncUidGenerator { UUID.randomUUID().toString().replace("-", "") },
                ScheduleMutationSink { }
            )
            conversations.create(Conversation(CONVERSATION_ID, "合成在线测试", clock.instant(), 0))
            val coordinator = AiCoordinator(
                scope,
                AiCoordinatorDependencies(
                    client, conversations, AiMessageBudgeter(), AiResponseParser(),
                    AiOperationExecutor(events), clock,
                    AiContextProvider(clock, VisibleScheduleSource { visible(events) }) { clock.zone }
                )
            )
            coordinator.onAppForegrounded()
            suspend fun turn(prompt: String, expectedChanges: Int, round: Int) {
                val transferred = key.copyOf()
                assertTrue(coordinator.submit(CONVERSATION_ID, prompt, settings, transferred))
                assertTrue("Coordinator must erase transferred key", transferred.all { it == '\u0000' })
                withTimeout(240_000) {
                    coordinator.state.first { it !is AiCoordinatorState.Working }
                }
                audit.printSafeDiagnostics(round)
                assertEquals("Round $round terminal state", AiCoordinatorState.Idle, coordinator.state.value)
                client.verifyLastCompletion()
                val messages = conversations.observeMessages(CONVERSATION_ID).first()
                val reply = messages.last { it.role == MessageRole.ASSISTANT }.content
                val applied = Regex("^已实际完成 ([0-9]+) 项日程操作").find(reply)?.groupValues?.get(1)
                val failed = Regex("^已实际完成 [0-9]+ 项日程操作，([0-9]+) 项未能执行")
                    .find(reply)?.groupValues?.get(1)
                val snapshot = visible(events).map {
                    "id=${it.id}:notify=${it.notificationEnabled}:alarm=${it.alarmEnabled}:timer=${it.timerMinutes}"
                }
                println("LIVE round=$round applied=$applied failed=$failed syntheticPolicies=$snapshot")
                assertTrue("Actual receipt count round $round", reply.startsWith("已实际完成 $expectedChanges 项日程操作。"))
                assertEquals(1, Regex("已实际完成").findAll(reply).count())
                assertFalse(reply.contains("Assistant reply"))
                val presentation = presentAssistantMessage(reply)
                assertTrue("Persisted visible model body round $round", presentation.modelBody.isNotBlank())
                assertTrue("Actual execution feedback round $round", presentation.executionFeedback != null)
                val thinking = messages.lastOrNull { it.role == MessageRole.THINK }?.content.orEmpty()
                val chinese = thinking.count { it in '\u4e00'..'\u9fff' }
                val englishWords = Regex("[A-Za-z]{2,}").findAll(thinking).count()
                println("LIVE round=$round changes=$expectedChanges thinkingCjk=$chinese englishWords=$englishWords " +
                    "httpCalls=${audit.size()} tokens=${client.totalTokens}")
                assertEquals(round, client.completedCalls)
                val storedConversation = conversations.findConversation(CONVERSATION_ID)!!
                val tokens = storedConversation.tokenCount
                assertEquals(client.lastRequestEstimate, storedConversation.lastInputTokenEstimate)
                assertEquals(settings.contextWindow, storedConversation.lastContextWindow)
                assertTrue(tokens > 0)
                assertEquals("Room must count native aggregated usage exactly once", client.totalTokens, tokens)
                println("LIVE round=$round businessCalls=${client.completedCalls} httpCalls=${audit.size()} " +
                    "tokens=$tokens contentUnchanged=true")
            }

            turn(
                "这是隔离测试，请直接创建且只创建以下四项，不需追问。" +
                    "1.时间段标题合成课程甲，2026-09-19 08:00至12:00，关闭通知和闹钟，开始后30分钟计时。" +
                    "2.时间段标题合成课程乙，2026-09-20 08:00至12:00，关闭通知、闹钟和计时。" +
                    "3.课程甲开始前30分钟单独创建重要闹钟提醒，标题合成提前闹钟，关闭普通通知和计时。" +
                    "4.2026-09-18 21:00创建标题合成前夜通知的提醒，开启普通通知，关闭闹钟和计时。",
                4, 1
            )
            val created = visible(events)
            assertEquals(4, created.size)
            assertEquals(4, created.map(Event::id).distinct().size)
            val firstCourse = created.single { it.title == "合成课程甲" }
            assertEquals(EventType.TIMESPAN, firstCourse.eventType)
            assertEquals(LocalDateTime.parse("2026-09-19T08:00"), firstCourse.startTime)
            assertEquals(LocalDateTime.parse("2026-09-19T12:00"), firstCourse.endTime)
            assertEquals(30, firstCourse.timerMinutes)
            assertFalse(firstCourse.alarmEnabled)
            assertFalse(firstCourse.notificationEnabled)
            val alarm = created.single { it.title == "合成提前闹钟" }
            assertEquals(EventType.REMINDER, alarm.eventType)
            assertEquals(LocalDateTime.parse("2026-09-19T07:30"), alarm.startTime)
            assertTrue(alarm.alarmEnabled)
            assertFalse(alarm.notificationEnabled)
            assertEquals(0, alarm.timerMinutes)
            val notification = created.single { it.title == "合成前夜通知" }
            assertEquals(LocalDateTime.parse("2026-09-18T21:00"), notification.startTime)
            assertTrue(notification.notificationEnabled)
            assertFalse(notification.alarmEnabled)
            assertEquals(4, events.observeMonthCounts(YearMonth.of(2026, 9)).first().values.sum())

            turn(
                "请按当前日历中的真实编号，把合成课程甲改名为合成课程已改，" +
                    "开始时间改为2026-09-19 09:00，结束仍为12:00，关闭计时；" +
                    "删除合成前夜通知，其余事项保持不变。",
                2, 2
            )
            val remaining = visible(events)
            assertEquals(3, remaining.size)
            val updated = remaining.single { it.title == "合成课程已改" }
            assertEquals(firstCourse.id, updated.id)
            assertEquals(LocalDateTime.parse("2026-09-19T09:00"), updated.startTime)
            assertEquals(0, updated.timerMinutes)
            assertFalse(remaining.any { it.id == notification.id })
            assertEquals(alarm, remaining.single { it.id == alarm.id })
            turn("这是隔离测试，请删除当前日历剩下的全部三个合成事项，使用当前可见编号。", 3, 3)
            assertTrue(visible(events).isEmpty())
            assertTrue(events.observeMonthCounts(YearMonth.of(2026, 9)).first().isEmpty())
            val expectedTomorrow = LocalDate.now(clock).plusDays(1)
            turn(
                "明天给我安排三件事：早上9点提醒喝水，只发普通通知；" +
                    "9点05分叫我起床，要响重要闹钟不要普通通知；" +
                    "9点10分到10点10分专注学习，开始时通知和重要闹钟都打开，" +
                    "开始后1分钟再用计时器提醒。标题分别叫合成喝水、合成起床、合成专注。",
                3, 4
            )
            val mixed = visible(events).associateBy(Event::title)
            assertEquals(3, mixed.size)
            val water = mixed.getValue("合成喝水")
            val wake = mixed.getValue("合成起床")
            val focus = mixed.getValue("合成专注")
            assertEquals(expectedTomorrow.atTime(9, 0), water.startTime)
            assertTrue(water.notificationEnabled)
            assertFalse(water.alarmEnabled)
            assertEquals(0, water.timerMinutes)
            assertEquals(expectedTomorrow.atTime(9, 5), wake.startTime)
            assertFalse(wake.notificationEnabled)
            assertTrue(wake.alarmEnabled)
            assertEquals(0, wake.timerMinutes)
            assertEquals(EventType.TIMESPAN, focus.eventType)
            assertEquals(expectedTomorrow.atTime(9, 10), focus.startTime)
            assertEquals(expectedTomorrow.atTime(10, 10), focus.endTime)
            assertTrue(focus.notificationEnabled)
            assertTrue(focus.alarmEnabled)
            assertEquals(1, focus.timerMinutes)
            turn(
                "合成喝水也打开重要闹钟并在开始后2分钟计时；" +
                    "合成起床改成只通知不响闹钟；合成专注的计时改成3分钟。其它设置和时间不要改。",
                3, 5
            )
            val changed = visible(events).associateBy(Event::id)
            assertEquals(water.copy(alarmEnabled = true, timerMinutes = 2).copy(
                updatedAt = changed.getValue(water.id).updatedAt
            ), changed.getValue(water.id))
            assertEquals(wake.copy(notificationEnabled = true, alarmEnabled = false).copy(
                updatedAt = changed.getValue(wake.id).updatedAt
            ), changed.getValue(wake.id))
            assertEquals(focus.copy(timerMinutes = 3).copy(
                updatedAt = changed.getValue(focus.id).updatedAt
            ), changed.getValue(focus.id))
            turn("把这三个合成事项的通知、闹钟和计时都关闭，保留事项本身。", 3, 6)
            val disabled = visible(events)
            assertEquals(3, disabled.size)
            assertTrue(disabled.all { !it.notificationEnabled && !it.alarmEnabled && it.timerMinutes == 0 })
            assertEquals(mixed.values.map(Event::id).toSet(), disabled.map(Event::id).toSet())
            turn("删除这三个合成事项。", 3, 7)
            assertTrue(visible(events).isEmpty())
            assertTrue(events.observeMonthCounts(YearMonth.of(2026, 9)).first().isEmpty())
            assertEquals(7, client.completedCalls)
            println("LIVE roomCreated=7 roomUpdated=7 roomDeleted=7 remaining=0 keyWipes=7 PASS")
        } finally {
            key.fill('\u0000')
            try {
                client.cancelInFlight()
                owner.cancelAndJoin()
            } finally {
                try {
                    openedDatabase?.close()
                } finally {
                    http.connectionPool.evictAll()
                    http.dispatcher.executorService.shutdownNow()
                    assertTrue(http.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    private suspend fun visible(events: EventService): List<Event> = events.observeRange(
        minOf(LocalDateTime.of(2026, 9, 1, 0, 0), LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay()),
        maxOf(LocalDateTime.of(2026, 10, 1, 0, 0), LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(3).atStartOfDay())
    ).first()

    private fun requiredEnvironment(name: String): String =
        requireNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) { "Missing live test environment: $name" }

    private companion object {
        const val CONVERSATION_ID = "live-synthetic"
    }
}

private class WipeCheckingClient(private val delegate: AiClient, private val audit: LiveHttpAudit) : AiClient by delegate {
    var completedCalls = 0
    var totalTokens = 0
    var lastRequestEstimate: Int? = null
    private var lastCompletion: AiCompletion? = null
    private var lastObservations = emptyList<HttpObservation>()
    private var keysWiped = true

    override suspend fun complete(
        settings: AiSettings,
        apiKey: CharArray,
        messages: List<AiRequestMessage>
    ): AiCompletion {
        val firstHttp = audit.size()
        lastRequestEstimate = AiMessageBudgeter().countTokens(messages)
        val visibleIds = messages.filter { it.role == "system" }.flatMap {
            Regex("(?m)^- id=([0-9]+) ").findAll(it.content).map { match -> match.groupValues[1] }.toList()
        }
        println("LIVE businessRequest=${completedCalls + 1} syntheticVisibleIds=$visibleIds")
        return try {
            delegate.complete(settings, apiKey, messages).also { completion ->
                lastObservations = audit.since(firstHttp)
                lastCompletion = completion
                println("LIVE nativeCompletionParse=${parseClassification(completion.content)}")
                totalTokens += completion.usage.totalTokens
                completedCalls++
            }
        } finally {
            keysWiped = keysWiped && apiKey.all { it == '\u0000' }
        }
    }

    // Assert on the test coroutine, so an audit failure cannot strand the
    // production coordinator in Working after an uncaught AssertionError.
    fun verifyLastCompletion() {
        assertTrue("Native client must erase its key", keysWiped)
        val completion = requireNotNull(lastCompletion)
        val lastSuccess = lastObservations.indexOfLast { it.status in 200..299 }
        assertTrue("Business response must have an observed successful HTTP response", lastSuccess >= 0)
        assertEquals("Native client must preserve final operation content byte for byte",
            lastObservations[lastSuccess].contentDigest, digest(completion.content))
        assertTrue("A business call has at most one compatibility fallback and one correction",
            lastObservations.size <= 3)
        val successful = lastObservations.filter { it.status in 200..299 }
        assertTrue("There is at most one format correction", successful.size <= 2)
        if (successful.size == 2) {
            assertFalse("A valid first operation batch with a visible reply must never be retried",
                successful.first().hasVisibleReply)
        }
        assertTrue("Final provider batch must include visible reply", successful.last().hasVisibleReply)
        assertEquals("All successful request usage must be aggregated", successful.sumOf { it.totalTokens },
            completion.usage.totalTokens)
        assertEquals(successful.sumOf { it.promptTokens }, completion.usage.promptTokens)
        assertEquals(successful.sumOf { it.completionTokens }, completion.usage.completionTokens)
    }
}

private data class HttpObservation(
    val status: Int,
    val contentDigest: String?,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val safeErrorCode: String?,
    val retryAfterSeconds: Long?,
    val finishReason: String,
    val operationDiagnostic: String,
    val hasVisibleReply: Boolean
)

private class LiveHttpAudit {
    private val observations = mutableListOf<HttpObservation>()

    @Synchronized
    fun size(): Int = observations.size

    @Synchronized
    fun since(index: Int): List<HttpObservation> = observations.drop(index)

    @Synchronized
    fun record(status: Int, body: String, retryAfter: String?) {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val content = runCatching {
            root?.get("choices")?.jsonArray?.firstOrNull()?.jsonObject?.get("message")
                ?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val usage = runCatching { root?.get("usage")?.jsonObject }.getOrNull()
        val finish = runCatching {
            root?.get("choices")?.jsonArray?.firstOrNull()?.jsonObject?.get("finish_reason")
                ?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val finishCategory = when (finish) {
            null -> "missing_or_null"
            "stop", "length", "tool_calls", "content_filter", "function_call" -> finish
            else -> "other"
        }
        fun count(name: String): Int = runCatching { usage?.get(name)?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0
        val errorCode = runCatching {
            root?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { Regex("[0-9]{1,8}").matches(it) }
        val retrySeconds = retryAfter?.takeIf { Regex("[0-9]{1,10}").matches(it) }?.toLongOrNull()
        observations += HttpObservation(status, content?.let(::digest), count("prompt_tokens"),
            count("completion_tokens"), count("total_tokens"), errorCode, retrySeconds,
            finishCategory, content?.let(::operationDiagnostic) ?: "missing_content",
            content?.let {
                (AiResponseParser().parse(it) as? AiParseResult.Operations)?.operations
                    ?.any { operation -> operation is AiOperation.Reply && operation.message.isNotBlank() }
            } == true)
    }

    @Synchronized
    fun printSafeDiagnostics(round: Int) {
        println("LIVE round=$round observedHttpCalls=${observations.size}")
        observations.forEachIndexed { index, observation ->
            println("LIVE httpIndex=${index + 1} status=${observation.status} " +
                "errorCode=${observation.safeErrorCode ?: "unavailable"} " +
                "retryAfterSeconds=${observation.retryAfterSeconds ?: "unavailable"} " +
                "finishReason=${observation.finishReason} visibleReply=${observation.hasVisibleReply} " +
                "schema=${observation.operationDiagnostic}")
        }
    }
}

private fun digest(content: String): String = MessageDigest.getInstance("SHA-256")
    .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun parseClassification(content: String): String = runCatching {
    when (val parsed = AiResponseParser().parse(content)) {
        is AiParseResult.Operations -> "operations_${parsed.operations.size}"
        is AiParseResult.PlainReply -> "plain_reply"
        is AiParseResult.Rejected -> "rejected"
    }
}.getOrDefault("parser_exception")

private fun operationDiagnostic(content: String): String {
    val classification = parseClassification(content)
    val trimmed = content.trim()
    val candidate = if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
        trimmed.substringAfter('\n').dropLast(3).trim()
    } else trimmed
    val root = runCatching { Json.parseToJsonElement(candidate) }.getOrNull()
        ?: return "$classification,json_invalid"
    val rootShape = if (root is JsonObject) {
        val allowed = setOf("operations", "action", "event_type", "title", "start_time", "end_time",
            "description", "estimated_duration", "notification_enabled", "alarm_enabled", "timer_minutes",
            "event_id", "message")
        "rootKnown=${root.keys.filter { it in allowed }},rootUnknownCount=${root.keys.count { it !in allowed }}"
    } else "rootType=${jsonType(root)}"
    val objects = when (root) {
        is JsonObject -> when {
            "operations" in root -> root["operations"] as? JsonArray
            "action" in root -> JsonArray(listOf(root))
            else -> null
        }
        is JsonArray -> root
        else -> null
    } ?: return "$classification,$rootShape,no_operation_array"
    val known = setOf("action", "event_type", "title", "start_time", "end_time", "description",
        "estimated_duration", "notification_enabled", "alarm_enabled", "timer_minutes", "event_id", "message")
    val envelopeUnknown = if (root is JsonObject && "operations" in root) root.keys.count { it != "operations" } else 0
    val details = objects.take(16).mapIndexed { index, element ->
        val operation = element as? JsonObject ?: return@mapIndexed "index=$index,not_object"
        val action = (operation["action"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it in setOf("add", "update", "delete", "reply") } ?: "unknown_or_missing"
        val eventType = (operation["event_type"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it in setOf("reminder", "timespan") } ?: "unknown_or_missing"
        val fieldTypes = known.filter { it in operation }.joinToString("|") { "$it:${jsonType(operation[it])}" }
        val times = listOf("start_time", "end_time").filter { it in operation }.joinToString("|") { name ->
            val value = operation[name]
            val valid = value is JsonPrimitive && value.isString && runCatching {
                WallClockCodec.parse(value.content)
            }.isSuccess
            "$name:${if (value == JsonNull) "null" else if (valid) "valid" else "invalid"}"
        }
        val id = operation["event_id"] as? JsonPrimitive
        val idType = if ("event_id" !in operation) "missing" else if (id != null && !id.isString &&
            id.booleanOrNull == null && (id.longOrNull ?: 0) > 0) "positive_integer" else "invalid"
        "index=$index,singleParse=${parseClassification(operation.toString())},action=$action," +
            "eventType=$eventType,unknownFields=${operation.keys.count { it !in known }}," +
            "fields=$fieldTypes,times=$times,id=$idType,idValue=${id?.longOrNull}," +
            "notify=${(operation["notification_enabled"] as? JsonPrimitive)?.booleanOrNull}," +
            "alarm=${(operation["alarm_enabled"] as? JsonPrimitive)?.booleanOrNull}," +
            "timer=${(operation["timer_minutes"] as? JsonPrimitive)?.intOrNull}"
    }
    return "$classification,$rootShape,operationCount=${objects.size},envelopeUnknown=$envelopeUnknown,details=$details"
}

private fun jsonType(value: JsonElement?): String = when (value) {
    null -> "missing"
    JsonNull -> "null"
    is JsonObject -> "object"
    is JsonArray -> "array"
    is JsonPrimitive -> when {
        value.isString -> "string"
        value.booleanOrNull != null -> "boolean"
        else -> "number"
    }
}
