package com.molotov.clender.app.ai

import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import com.molotov.clender.data.network.ai.AiClient
import com.molotov.clender.data.network.ai.AiCompletion
import com.molotov.clender.data.network.ai.AiCompletionUsage
import com.molotov.clender.data.network.ai.AiHttpException
import com.molotov.clender.data.network.ai.AiTimeoutException
import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.data.settings.ThinkingEffort
import com.molotov.clender.domain.ai.AiContextProvider
import com.molotov.clender.domain.ai.AiMessageBudgeter
import com.molotov.clender.domain.ai.AiOperationExecutor
import com.molotov.clender.domain.ai.AiResponseParser
import com.molotov.clender.domain.ai.VisibleScheduleSource
import com.molotov.clender.domain.conversation.ConversationRepository
import com.molotov.clender.domain.event.EventRepository
import com.molotov.clender.domain.event.EventService
import com.molotov.clender.domain.event.ScheduleMutationSink
import com.molotov.clender.domain.event.SyncUidGenerator
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conversations = CoordinatorConversationRepository()
    private val events = CoordinatorEventRepository()
    private val client = ControllableAiClient()
    private val coordinator by lazy { coordinator() }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun unauthorizedProviderResponseHasRecoverableAuthenticationStatus() =
        httpStatus(401, "AUTHENTICATION")

    @Test
    fun forbiddenProviderResponseHasRecoverableAuthenticationStatus() =
        httpStatus(403, "AUTHENTICATION")

    @Test
    fun busyProviderResponseHasRecoverableRateLimitStatus() = httpStatus(429, "RATE_LIMIT")

    @Test
    fun invalidParameterResponseHasRecoverableRequestStatus() =
        httpStatus(400, "REQUEST_PARAMETERS")

    @Test
    fun unsupportedParameterResponseHasRecoverableRequestStatus() =
        httpStatus(422, "REQUEST_PARAMETERS")

    @Test
    fun serverFailureRetainsGenericProviderStatus() = httpStatus(503, "PROVIDER")

    private fun httpStatus(status: Int, expected: String) = runBlocking {
        conversations.create(conversation("a"))
        assertTrue(coordinator.submit("a", "合成请求", settings(), testKey()))
        waitUntil { client.completeCalls.get() == 1 }
        client.nextCompletion.completeExceptionally(
            AiHttpException(status, "PRIVATE_BODY_SENTINEL")
        )
        waitUntil { coordinator.state.value is AiCoordinatorState.Failed }
        assertEquals(expected, (coordinator.state.value as AiCoordinatorState.Failed).error.name)
        assertEquals(listOf(MessageRole.USER), conversations.messages.getValue("a").map { it.role })
    }

    @Test
    fun rejectedOperationSetsInvalidResponseWithoutClaimingCompletion() = runBlocking {
        conversations.create(conversation("a"))
        assertTrue(coordinator.submit("a", "创建提醒", settings(), testKey()))
        waitUntil { client.completeCalls.get() == 1 }
        client.nextCompletion.complete(replyCompletion("""{"operations":[{"action":"shell"}]}"""))
        waitUntil { coordinator.state.value !is AiCoordinatorState.Working }
        assertEquals(
            AiCoordinatorState.Failed(AiCoordinatorError.INVALID_RESPONSE),
            coordinator.state.value
        )
        assertEquals(2, conversations.messages.getValue("a").size)
        assertEquals(2, conversations.findConversation("a")?.tokenCount)
    }

    @Test
    fun legacyApplicationReceiptsAreNotEchoedIntoProviderHistory() = runBlocking {
        conversations.create(conversation("a"))
        conversations.appendMessageAndIncrementTokens(
            "a",
            MessageRole.ASSISTANT,
            "No schedule changes were made. 本轮未修改日程。\n\nAssistant reply / 模型回复：\n请核对日期",
            Instant.parse("2026-09-08T00:00:00Z"),
            0
        )
        assertTrue(coordinator.submit("a", "再次核对", settings(), testKey()))
        waitUntil { client.completeCalls.get() == 1 }
        assertEquals("请核对日期", client.lastMessages.single { it.role == "assistant" }.content)
        client.nextCompletion.complete(replyCompletion("核对完成"))
        waitUntil { coordinator.state.value == AiCoordinatorState.Idle }
    }

    @Test
    fun t66LegacySystemPromptNeverOverridesEmbeddedOperationContract() = runBlocking {
        conversations.create(conversation("a"))
        val legacy = "LEGACY_SYSTEM_SENTINEL"
        assertTrue(
            coordinator.submit("a", "hello", settings().copy(systemPrompt = legacy), testKey())
        )
        waitUntil { client.completeCalls.get() == 1 }
        assertFalse(client.lastMessages.any { it.content.contains(legacy) })
        client.nextCompletion.complete(replyCompletion("hello"))
        waitUntil { coordinator.state.value == AiCoordinatorState.Idle }
    }

    @Test
    fun singleFlightRejectsSecondSubmissionAndCapturesOriginatingConversation() = runBlocking {
        conversations.create(conversation("a"))
        conversations.create(conversation("b"))

        val firstKey = testKey()
        val busyKey = testKey()
        assertTrue(coordinator.submit("a", "first", settings(), firstKey))
        waitUntil { client.completeCalls.get() == 1 }
        assertTrue(firstKey.all { it == '\u0000' })
        assertFalse(coordinator.submit("b", "second", settings(), busyKey))
        assertTrue(busyKey.all { it == '\u0000' })

        client.nextCompletion.complete(replyCompletion("reply-a"))
        waitUntil { coordinator.state.value == AiCoordinatorState.Idle }

        assertEquals(
            listOf(
                MessageRole.USER to "first",
                MessageRole.ASSISTANT to
                    "本轮未修改日程。\n\n模型回复：\nreply-a"
            ),
            conversations.messages.getValue("a").map { it.role to it.content }
        )
        assertTrue(conversations.messages["b"].orEmpty().isEmpty())
        assertEquals(1, client.completeCalls.get())
    }

    @Test
    fun replacingStateObserverForConfigurationChangeDoesNotCancelAppScopedRequest() = runBlocking {
        conversations.create(conversation("a"))
        val firstObserver = async {
            coordinator.state.first { it is AiCoordinatorState.Working }
        }
        val key = testKey()
        assertTrue(coordinator.submit("a", "survive recreation", settings(), key))
        assertTrue(firstObserver.await() is AiCoordinatorState.Working)
        val secondObserver = async {
            coordinator.state.first { it == AiCoordinatorState.Idle }
        }
        assertEquals(0, client.cancelCalls.get())

        client.nextCompletion.complete(replyCompletion("done"))
        assertEquals(AiCoordinatorState.Idle, secondObserver.await())
        assertEquals(0, client.cancelCalls.get())
        assertTrue(key.all { it == '\u0000' })
    }

    @Test
    fun backgroundCancellationPerformsNoOperationNoAssistantWriteAndNoRetry() = runBlocking {
        conversations.create(conversation("a"))
        val key = testKey()
        assertTrue(coordinator.submit("a", "cancel", settings(), key))
        waitUntil { client.completeCalls.get() == 1 }

        coordinator.onAppBackgrounded()
        waitUntil { coordinator.state.value is AiCoordinatorState.Cancelled }
        client.nextCompletion.complete(
            AiCompletion(
                content = "{\"action\":\"add\",\"event_type\":\"reminder\"," +
                    "\"title\":\"must-not-run\",\"start_time\":\"2026-08-09 09:00\"}",
                reasoningContent = "",
                usage = AiCompletionUsage(1, 1, 2)
            )
        )
        delay(50)

        assertEquals(1, client.completeCalls.get())
        assertEquals(1, client.cancelCalls.get())
        assertTrue(key.all { it == '\u0000' })
        assertEquals(0, events.writeCalls)
        assertEquals(
            listOf(MessageRole.USER),
            conversations.messages.getValue("a").map(Message::role)
        )
    }

    @Test
    fun backgroundGateRejectsBeforeSubmitAndClosesPendingLaunchRace() = runBlocking {
        conversations.create(conversation("a"))
        coordinator.onAppBackgrounded()
        val alreadyBackgroundedKey = testKey()

        assertFalse(coordinator.submit("a", "must reject", settings(), alreadyBackgroundedKey))
        assertTrue(alreadyBackgroundedKey.all { it == '\u0000' })
        assertTrue(conversations.messages["a"].orEmpty().isEmpty())
        assertEquals(0, client.completeCalls.get())

        coordinator.onAppForegrounded()
        val committed = CompletableDeferred<Unit>()
        val allowReturn = CompletableDeferred<Unit>()
        conversations.appendCommitted = committed
        conversations.allowAppendReturn = allowReturn
        val racingKey = testKey()
        val submission = async {
            coordinator.submit("a", "persist before stop", settings(), racingKey)
        }
        committed.await()

        coordinator.onAppBackgrounded()
        allowReturn.complete(Unit)

        assertFalse(submission.await())
        assertTrue(racingKey.all { it == '\u0000' })
        assertEquals(0, client.completeCalls.get())
        assertEquals(
            listOf(MessageRole.USER),
            conversations.messages.getValue("a").map(Message::role)
        )
    }

    @Test
    fun backgroundingAfterCompletionDeliveryButBeforePersistenceExecutesNothing() = runBlocking {
        conversations.create(conversation("a"))
        val raceCoordinator = coordinator()
        client.beforeCompletionReturn = raceCoordinator::onAppBackgrounded
        val key = testKey()
        assertTrue(raceCoordinator.submit("a", "race", settings(), key))
        waitUntil { client.completeCalls.get() == 1 }
        client.nextCompletion.complete(
            AiCompletion(
                content = "{\"action\":\"add\",\"event_type\":\"reminder\"," +
                    "\"title\":\"must-not-run\",\"start_time\":\"2026-08-09 09:00\"}",
                reasoningContent = "must-not-store",
                usage = AiCompletionUsage(1, 1, 2)
            )
        )
        waitUntil { raceCoordinator.state.value is AiCoordinatorState.Cancelled }

        assertTrue(key.all { it == '\u0000' })
        assertEquals(0, events.writeCalls)
        assertEquals(
            listOf(MessageRole.USER),
            conversations.messages.getValue("a").map(Message::role)
        )
        assertEquals(1, client.completeCalls.get())
    }

    @Test
    fun transportFailureIsRestrictedAndNeverAutomaticallyRetried() = runBlocking {
        conversations.create(conversation("a"))
        client.failure = AiTimeoutException()

        val key = testKey()
        assertTrue(coordinator.submit("a", "fail once", settings(), key))
        waitUntil { coordinator.state.value is AiCoordinatorState.Failed }

        val failed = coordinator.state.value as AiCoordinatorState.Failed
        assertEquals(1, client.completeCalls.get())
        assertTrue(key.all { it == '\u0000' })
        assertEquals(AiCoordinatorError.TIMEOUT, failed.error)
        assertFalse(failed.toString().contains("fail once"))
        assertEquals(0, events.writeCalls)
        assertEquals(
            listOf(MessageRole.USER),
            conversations.messages.getValue("a").map(Message::role)
        )
    }

    @Test
    fun unknownConversationAndBlankInputFailBeforeNetwork() = runBlocking {
        conversations.create(conversation("a"))
        val missingKey = testKey()
        val blankKey = testKey()

        assertFalse(coordinator.submit("missing", "hello", settings(), missingKey))
        assertFalse(coordinator.submit("a", " \t", settings(), blankKey))
        delay(50)

        assertEquals(0, client.completeCalls.get())
        assertTrue(missingKey.all { it == '\u0000' })
        assertTrue(blankKey.all { it == '\u0000' })
        assertTrue(conversations.messages["a"].orEmpty().isEmpty())
    }

    @Test
    fun successfulChainPersistsThinkingReplyTokenDeltasAndExecutesScheduleOnce() = runBlocking {
        conversations.create(conversation("a"))
        val key = testKey()
        assertTrue(coordinator.submit("a", "add it", settings(), key))
        waitUntil { client.completeCalls.get() == 1 }

        client.nextCompletion.complete(
            AiCompletion(
                content =
                    "{\"operations\":[" +
                        "{\"action\":\"add\",\"event_type\":\"reminder\"," +
                        "\"title\":\"created\",\"start_time\":\"2026-08-09 09:00\"}," +
                        "{\"action\":\"reply\",\"message\":\"done\"}]}",
                reasoningContent = "private thinking",
                usage = AiCompletionUsage(4, 5, 9)
            )
        )
        waitUntil { coordinator.state.value == AiCoordinatorState.Idle }

        assertTrue(key.all { it == '\u0000' })
        assertEquals(1, events.writeCalls)
        assertEquals(
            listOf(MessageRole.USER, MessageRole.THINK, MessageRole.ASSISTANT),
            conversations.messages.getValue("a").map(Message::role)
        )
        assertEquals(
            listOf(
                "add it",
                "private thinking",
                "已实际完成 1 项日程操作。\n\n模型回复：\ndone"
            ),
            conversations.messages.getValue("a").map(Message::content)
        )
        assertTrue(conversations.tokenDeltas.all { it >= 0 })
        val stored = requireNotNull(conversations.findConversation("a"))
        assertEquals(conversations.tokenDeltas.sum(), stored.tokenCount)
        assertTrue(stored.tokenCount >= 9)
    }

    @Test
    fun defaultSystemContextUsesLocalTimeButMessageMetadataRemainsUtc() = runBlocking {
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val coordinator = coordinator(contextProvider = AiContextProvider(fixedClock, events))
            conversations.create(conversation("a"))
            val key = testKey()
            assertTrue(coordinator.submit("a", "synthetic time check", settings(), key))
            waitUntil { client.completeCalls.get() == 1 }
            val system = client.lastMessages.first { it.role == "system" }.content
            client.nextCompletion.complete(replyCompletion("done"))
            waitUntil { coordinator.state.value == AiCoordinatorState.Idle }

            assertTrue(
                system.contains("Current local date/time: 2026-08-09 11:00; weekday: Sunday.")
            )
            val messages = conversations.messages.getValue("a")
            assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), messages.map { it.role })
            assertTrue(messages.all { it.timestamp == fixedClock.instant() })
            assertTrue(key.all { it == '\u0000' })
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun requestContextContainsContractNowWeekdayAndOnlyVisibleScheduleSummaries() = runBlocking {
        conversations.create(conversation("a"))
        events.contextEvents += contextEvent(1, "visible", deleted = false)
        events.contextEvents += contextEvent(2, "tombstone", deleted = true)
        val key = testKey()

        assertTrue(coordinator.submit("a", "context", settings(), key))
        waitUntil { client.completeCalls.get() == 1 }

        val system = client.lastMessages.first { it.role == "system" }.content
        assertTrue(system.contains("Clender AI operation contract"))
        assertTrue(system.contains("2026-08-09 03:00"))
        assertTrue(system.contains("Sunday"))
        assertTrue(system.contains("visible"))
        assertFalse(system.contains("tombstone"))
        assertFalse(system.contains("sensitive-description"))
        client.nextCompletion.complete(replyCompletion("done"))
        waitUntil { coordinator.state.value == AiCoordinatorState.Idle }
    }

    @Test
    fun repositoryFailureAndAlreadyCancelledScopeAlwaysWipeTransferredKeys() = runBlocking {
        conversations.create(conversation("a"))
        conversations.failFind = true
        val repositoryFailureKey = testKey()
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                coordinator.submit("a", "failure", settings(), repositoryFailureKey)
            }
        }
        assertTrue(repositoryFailureKey.all { it == '\u0000' })
        conversations.failFind = false

        val cancelledScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        cancelledScope.cancel(CancellationException("test cancelled scope"))
        val cancelledCoordinator = coordinator(cancelledScope)
        val cancelledKey = testKey()
        assertFalse(cancelledCoordinator.submit("a", "never starts", settings(), cancelledKey))
        assertTrue(cancelledKey.all { it == '\u0000' })
        assertEquals(AiCoordinatorState.Idle, cancelledCoordinator.state.value)
        assertTrue(conversations.messages["a"].orEmpty().isEmpty())
        assertEquals(0, client.completeCalls.get())
    }

    private fun coordinator(
        coordinatorScope: CoroutineScope = scope,
        contextProvider: AiContextProvider =
            AiContextProvider(fixedClock, events) { fixedClock.zone }
    ): AiCoordinator {
        val eventService = EventService(
            repository = events,
            clock = fixedClock,
            uidGenerator = SyncUidGenerator { "0123456789abcdef0123456789abcdef" },
            mutationSink = ScheduleMutationSink { }
        )
        return AiCoordinator(
            scope = coordinatorScope,
            dependencies = AiCoordinatorDependencies(
                client = client,
                conversations = conversations,
                budgeter = AiMessageBudgeter(),
                parser = AiResponseParser(),
                executor = AiOperationExecutor(eventService),
                clock = fixedClock,
                contextProvider = contextProvider
            )
        ).also(AiCoordinator::onAppForegrounded)
    }

    private fun conversation(id: String) = Conversation(id, id, fixedClock.instant(), 0)

    private fun settings() = AiSettings(
        endpoint = "https://example.invalid",
        model = "model-test",
        temperature = 0.7,
        maxOutputTokens = 512,
        contextWindow = 8_192,
        thinkingEnabled = true,
        thinkingEffort = ThinkingEffort.HIGH,
        systemPrompt = "system",
        personality = ""
    )

    private fun testKey(): CharArray = CharArray(8) { index -> ('a'.code + index).toChar() }

    private fun contextEvent(id: Long, title: String, deleted: Boolean) = Event(
        id = id,
        eventType = com.molotov.clender.core.model.EventType.REMINDER,
        title = title,
        startTime = LocalDateTime.of(2026, 8, 9, 9, 0),
        endTime = null,
        description = "sensitive-description",
        estimatedDurationMinutes = 0,
        createdAt = fixedClock.instant(),
        syncUid = id.toString().padStart(32, '0'),
        updatedAt = fixedClock.instant(),
        deletedAt = fixedClock.instant().takeIf { deleted }
    )

    private suspend fun waitUntil(predicate: () -> Boolean) {
        withTimeout(2_000) {
            while (!predicate()) delay(10)
        }
    }

    private companion object {
        val fixedClock: Clock = Clock.fixed(
            Instant.parse("2026-08-09T03:00:00Z"),
            ZoneOffset.UTC
        )
    }
}

private class ControllableAiClient : AiClient {
    val completeCalls = AtomicInteger()
    val cancelCalls = AtomicInteger()
    val nextCompletion = CompletableDeferred<AiCompletion>()
    var failure: Throwable? = null
    var lastMessages = emptyList<com.molotov.clender.domain.ai.AiRequestMessage>()
    var beforeCompletionReturn: (() -> Unit)? = null

    override suspend fun fetchModels(settings: AiSettings, apiKey: CharArray): List<String> =
        error("not used")

    override suspend fun complete(
        settings: AiSettings,
        apiKey: CharArray,
        messages: List<com.molotov.clender.domain.ai.AiRequestMessage>
    ): AiCompletion {
        completeCalls.incrementAndGet()
        lastMessages = messages
        apiKey.fill('\u0000')
        failure?.let { throw it }
        val completion = nextCompletion.await()
        beforeCompletionReturn?.invoke()
        return completion
    }

    override fun cancelInFlight() {
        cancelCalls.incrementAndGet()
    }
}

private class CoordinatorConversationRepository : ConversationRepository {
    val conversations = linkedMapOf<String, Conversation>()
    val messages = linkedMapOf<String, MutableList<Message>>()
    val tokenDeltas = mutableListOf<Int>()
    private var nextMessageId = 1L
    var failFind = false
    var appendCommitted: CompletableDeferred<Unit>? = null
    var allowAppendReturn: CompletableDeferred<Unit>? = null

    override suspend fun create(conversation: Conversation): Conversation = conversation.also {
        conversations[it.id] = it
    }

    override suspend fun findConversation(id: String): Conversation? {
        check(!failFind) { "test repository failure" }
        return conversations[id]
    }

    override suspend fun listConversations(): List<Conversation> = conversations.values.toList()

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        flowOf(messages[conversationId].orEmpty())

    override suspend fun appendMessageAndIncrementTokens(
        conversationId: String,
        role: MessageRole,
        content: String,
        timestamp: Instant,
        tokenDelta: Int
    ): Message {
        val current = requireNotNull(conversations[conversationId])
        val message = Message(nextMessageId++, conversationId, role, content, timestamp)
        tokenDeltas += tokenDelta
        messages.getOrPut(conversationId, ::mutableListOf).add(message)
        conversations[conversationId] = current.copy(tokenCount = current.tokenCount + tokenDelta)
        appendCommitted?.complete(Unit)
        allowAppendReturn?.await()
        appendCommitted = null
        allowAppendReturn = null
        return message
    }

    override suspend fun rename(id: String, title: String): Conversation? =
        conversations[id]?.copy(title = title)?.also { conversations[id] = it }

    override suspend fun clearMessagesAndResetTokens(id: String): Boolean {
        val current = conversations[id] ?: return false
        messages.remove(id)
        conversations[id] = current.copy(tokenCount = 0)
        return true
    }

    override suspend fun delete(id: String): Boolean = conversations.remove(id) != null
}

private class CoordinatorEventRepository :
    EventRepository,
    VisibleScheduleSource {
    var writeCalls = 0
    val contextEvents = mutableListOf<Event>()
    private val values = linkedMapOf<Long, Event>()

    override suspend fun findById(id: Long): Event? = values[id]

    override suspend fun insert(event: Event): Event {
        writeCalls += 1
        return event.copy(id = values.size + 1L).also { values[it.id] = it }
    }

    override suspend fun update(event: Event): Event {
        writeCalls += 1
        values[event.id] = event
        return event
    }

    override fun observeRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> =
        flowOf(values.values.toList())

    override suspend fun visibleEvents(): List<Event> = contextEvents.toList()
}

private fun replyCompletion(content: String) = AiCompletion(
    content = content,
    reasoningContent = "",
    usage = AiCompletionUsage(1, 1, 2)
)
