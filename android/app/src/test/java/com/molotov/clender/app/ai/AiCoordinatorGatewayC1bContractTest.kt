package com.molotov.clender.app.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import com.molotov.clender.data.network.ai.AiClient
import com.molotov.clender.data.network.ai.AiCompletion
import com.molotov.clender.data.network.ai.AiCompletionUsage
import com.molotov.clender.data.network.ai.AiConfigurationException
import com.molotov.clender.data.network.ai.AiHttpException
import com.molotov.clender.data.network.ai.AiNetworkException
import com.molotov.clender.data.network.ai.AiProtocolException
import com.molotov.clender.data.network.ai.AiResponseTooLargeException
import com.molotov.clender.data.network.ai.AiTimeoutException
import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.data.settings.AppPreferencesState
import com.molotov.clender.data.settings.DataStoreAppPreferences
import com.molotov.clender.data.settings.SecretAlias
import com.molotov.clender.data.settings.SecretStore
import com.molotov.clender.domain.ai.AiContextProvider
import com.molotov.clender.domain.ai.AiMessageBudgeter
import com.molotov.clender.domain.ai.AiOperationExecutor
import com.molotov.clender.domain.ai.AiRequestMessage
import com.molotov.clender.domain.ai.AiResponseParser
import com.molotov.clender.domain.ai.VisibleScheduleSource
import com.molotov.clender.domain.conversation.ConversationRepository
import com.molotov.clender.domain.event.EventRepository
import com.molotov.clender.domain.event.EventService
import com.molotov.clender.domain.event.ScheduleMutation
import com.molotov.clender.domain.event.ScheduleMutationSink
import com.molotov.clender.domain.event.SyncUidGenerator
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AiCoordinatorGatewayC1bContractTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        scopes.forEach(CoroutineScope::cancel)
        files.forEach(File::delete)
    }

    @Test
    fun coordinatorMapsTransportAndInternalFailuresToFiniteCodesWithoutLeakingDetails() =
        runBlocking {
            val cases = listOf(
                AiConfigurationException("configuration-secret") to
                    AiCoordinatorError.CONFIGURATION,
                AiTimeoutException() to AiCoordinatorError.TIMEOUT,
                AiNetworkException(IllegalStateException("network-secret")) to
                    AiCoordinatorError.NETWORK,
                AiHttpException(503, "provider-secret-body") to AiCoordinatorError.PROVIDER,
                AiProtocolException() to AiCoordinatorError.INVALID_RESPONSE,
                AiResponseTooLargeException() to AiCoordinatorError.INVALID_RESPONSE,
                IllegalStateException("internal-secret-path") to AiCoordinatorError.INTERNAL
            )

            cases.forEachIndexed { index, (failure, expected) ->
                val fixture = fixture(FailingC1bAiClient(failure))
                fixture.conversations.create(conversation("failure-$index"))
                val key = "gateway-test-key-$index".toCharArray()

                assertTrue(
                    fixture.coordinator.submit(
                        "failure-$index",
                        "private-prompt-$index",
                        configuredState().ai,
                        key
                    )
                )
                waitUntil { fixture.coordinator.state.value is AiCoordinatorState.Failed }

                val failed = fixture.coordinator.state.value as AiCoordinatorState.Failed
                assertEquals(expected, failed.error)
                assertFalse(failed.toString().contains("private-prompt"))
                assertFalse(failed.toString().contains("gateway-test-key"))
                assertFalse(failed.toString().contains("provider-secret-body"))
                assertFalse(failed.toString().contains("example.invalid"))
                assertFalse(failed.toString().contains("failure-$index"))
                assertTrue(key.all { it == '\u0000' })
            }
        }

    @Test
    fun acknowledgementOnlyClearsFailedOrCancelledAndNeverCancelsWorking() = runBlocking {
        val failure = fixture(FailingC1bAiClient(AiTimeoutException()))
        failure.conversations.create(conversation("failed"))
        assertTrue(
            failure.coordinator.submit(
                "failed",
                "message",
                configuredState().ai,
                testKey()
            )
        )
        waitUntil { failure.coordinator.state.value is AiCoordinatorState.Failed }
        failure.coordinator.acknowledgeTerminal()
        assertEquals(AiCoordinatorState.Idle, failure.coordinator.state.value)

        val blockingClient = BlockingC1bAiClient()
        val working = fixture(blockingClient)
        working.conversations.create(conversation("working"))
        assertTrue(
            working.coordinator.submit(
                "working",
                "message",
                configuredState().ai,
                testKey()
            )
        )
        waitUntil { working.coordinator.state.value is AiCoordinatorState.Working }
        working.coordinator.acknowledgeTerminal()
        assertTrue(working.coordinator.state.value is AiCoordinatorState.Working)
        assertEquals(0, blockingClient.cancelCalls.get())
        blockingClient.completion.complete(reply("done"))
        waitUntil { working.coordinator.state.value == AiCoordinatorState.Idle }
    }

    @Test
    fun gatewayRejectsBlankAndMissingConfigurationBeforeSecretOrNetworkAccess() = runBlocking {
        val client = BlockingC1bAiClient()
        val fixture = fixture(client)
        fixture.conversations.create(conversation("a"))
        val secrets = RecordingC1bSecretStore("not-a-real-key".toCharArray())
        val missingEndpoint = gateway(AppPreferencesState.DEFAULT, secrets, fixture.coordinator)

        assertEquals(
            AiSubmissionDecision.REJECTED,
            missingEndpoint.submit("a", " \t\n")
        )
        assertEquals(
            AiSubmissionDecision.UNCONFIGURED,
            missingEndpoint.submit("a", "hello")
        )
        assertEquals(0, secrets.getCalls)
        assertEquals(0, client.completeCalls.get())

        val missingModel = gateway(
            configuredState().copy(ai = configuredState().ai.copy(model = "")),
            secrets,
            fixture.coordinator
        )
        assertEquals(AiSubmissionDecision.UNCONFIGURED, missingModel.submit("a", "hello"))
        assertEquals(0, secrets.getCalls)
        assertEquals(0, client.completeCalls.get())
    }

    @Test
    fun gatewayClassifiesMissingBlankAndFailingSecretWithoutCallingNetwork() = runBlocking {
        val client = BlockingC1bAiClient()
        val fixture = fixture(client)
        fixture.conversations.create(conversation("a"))

        val missing = RecordingC1bSecretStore(null)
        assertEquals(
            AiSubmissionDecision.UNCONFIGURED,
            gateway(configuredState(), missing, fixture.coordinator).submit("a", "hello")
        )
        val blank = RecordingC1bSecretStore(" \t".toCharArray())
        assertEquals(
            AiSubmissionDecision.UNCONFIGURED,
            gateway(configuredState(), blank, fixture.coordinator).submit("a", "hello")
        )
        assertTrue(requireNotNull(blank.lastReturned).all { it == '\u0000' })

        val broken = RecordingC1bSecretStore(null, IllegalStateException("bad-envelope"))
        assertEquals(
            AiSubmissionDecision.SECRET_FAILURE,
            gateway(configuredState(), broken, fixture.coordinator).submit("a", "hello")
        )
        assertEquals(0, client.completeCalls.get())
    }

    @Test
    fun gatewayMapsSettingsReadFailureWithoutReadingSecretOrLeakingException() = runBlocking {
        val client = BlockingC1bAiClient()
        val fixture = fixture(client)
        fixture.conversations.create(conversation("a"))
        val secrets = RecordingC1bSecretStore("not-a-real-key".toCharArray())
        val failingStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                throw IllegalStateException("settings-private-path")
            }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences
            ): Preferences = error("not used")
        }
        val gateway = ConfiguredAiSubmissionGateway(
            DataStoreAppPreferences(failingStore),
            secrets,
            fixture.coordinator
        )

        assertEquals(AiSubmissionDecision.SETTINGS_FAILURE, gateway.submit("a", "hello"))
        assertEquals(0, secrets.getCalls)
        assertEquals(0, client.completeCalls.get())
        assertFalse(gateway.state.value.toString().contains("settings-private-path"))
    }

    @Test
    fun gatewayAcceptedBusyRejectedAndBackgroundPathsAlwaysWipeReturnedKeys() = runBlocking {
        val client = BlockingC1bAiClient()
        val fixture = fixture(client)
        fixture.conversations.create(conversation("a"))
        val secrets = RecordingC1bSecretStore("not-a-real-key".toCharArray())
        val gateway = gateway(configuredState(), secrets, fixture.coordinator)

        assertEquals(AiSubmissionDecision.ACCEPTED, gateway.submit("a", "first"))
        waitUntil { client.completeCalls.get() == 1 }
        assertTrue(requireNotNull(secrets.returnedValues[0]).all { it == '\u0000' })
        assertEquals(AiSubmissionDecision.BUSY, gateway.submit("a", "second"))
        assertEquals(1, secrets.getCalls)
        assertEquals(1, secrets.returnedValues.size)
        assertEquals(1, client.completeCalls.get())

        client.completion.complete(reply("done"))
        waitUntil { fixture.coordinator.state.value == AiCoordinatorState.Idle }
        assertEquals(AiSubmissionDecision.REJECTED, gateway.submit("missing", "third"))
        assertTrue(requireNotNull(secrets.returnedValues[1]).all { it == '\u0000' })
        fixture.coordinator.onAppBackgrounded()
        assertEquals(AiSubmissionDecision.REJECTED, gateway.submit("a", "background"))
        assertTrue(requireNotNull(secrets.returnedValues[2]).all { it == '\u0000' })
        assertEquals(1, client.completeCalls.get())
    }

    @Test
    fun backgroundCancellationStoresNoThinkingReplyOrOperationAndDoesNotRetry() = runBlocking {
        val client = BlockingC1bAiClient()
        val fixture = fixture(client)
        fixture.conversations.create(conversation("a"))
        val key = testKey()
        assertTrue(
            fixture.coordinator.submit("a", "cancel", configuredState().ai, key)
        )
        waitUntil { client.completeCalls.get() == 1 }

        fixture.coordinator.onAppBackgrounded()
        client.completion.complete(
            AiCompletion(
                content =
                    """{"operations":[{"action":"add","event_type":"reminder",""" +
                        """"title":"must-not-run","start_time":"2026-08-09 09:00"}]}""",
                reasoningContent = "must-not-store",
                usage = AiCompletionUsage(1, 1, 2)
            )
        )
        waitUntil { fixture.coordinator.state.value == AiCoordinatorState.Cancelled }
        delay(50)

        assertEquals(1, client.completeCalls.get())
        assertEquals(1, client.cancelCalls.get())
        assertEquals(0, fixture.events.writeCalls)
        assertEquals(
            listOf(MessageRole.USER),
            fixture.conversations.messages.getValue("a").map(Message::role)
        )
        assertTrue(key.all { it == '\u0000' })
    }

    @Test
    fun malformedAndDangerousResponsesNeverWriteEventsWhileValidBatchMutatesOnce() = runBlocking {
        val unsafeBodies = listOf(
            "{malformed-json",
            """{"operations":[{"action":"shell","command":"erase"}]}""",
            """{"operations":[{"action":"delete","event_id":-1,"unknown":"x"}]}""",
            """{"operations":[{"action":"add","event_type":"reminder","title":"bad","start_time":"not-time"}]}"""
        )
        unsafeBodies.forEachIndexed { index, body ->
            val client = ImmediateC1bAiClient(reply(body))
            val fixture = fixture(client)
            fixture.conversations.create(conversation("unsafe-$index"))
            assertTrue(
                fixture.coordinator.submit(
                    "unsafe-$index",
                    "request",
                    configuredState().ai,
                    testKey()
                )
            )
            waitUntil { fixture.coordinator.state.value !is AiCoordinatorState.Working }
            // The first body has no operation field and remains a plain, non-writing reply.
            val expectedState = if (index == 0) {
                AiCoordinatorState.Idle
            } else {
                AiCoordinatorState.Failed(AiCoordinatorError.INVALID_RESPONSE)
            }
            assertEquals(expectedState, fixture.coordinator.state.value)
            assertEquals(0, fixture.events.writeCalls)
            assertTrue(fixture.mutations.isEmpty())
        }

        val valid = ImmediateC1bAiClient(
            reply(
                """{"operations":[{"action":"add","event_type":"reminder",""" +
                    """"title":"one","start_time":"2026-08-09 09:00"},""" +
                    """{"action":"add","event_type":"reminder","title":"two",""" +
                    """"start_time":"2026-08-09 10:00"}]}"""
            )
        )
        val fixture = fixture(valid)
        fixture.conversations.create(conversation("batch"))
        assertTrue(
            fixture.coordinator.submit("batch", "request", configuredState().ai, testKey())
        )
        waitUntil { fixture.coordinator.state.value == AiCoordinatorState.Idle }
        assertEquals(2, fixture.events.writeCalls)
        val mutation = fixture.mutations.single() as ScheduleMutation.Batch
        assertEquals(2, mutation.mutations.size)
    }

    private suspend fun gateway(
        state: AppPreferencesState,
        secrets: SecretStore,
        coordinator: AiCoordinator
    ): ConfiguredAiSubmissionGateway {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "datastore/c1b-${UUID.randomUUID()}.preferences_pb")
        files += file
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        val preferences = DataStoreAppPreferences(
            PreferenceDataStoreFactory.create(scope = scope) { file }
        )
        preferences.save(state)
        return ConfiguredAiSubmissionGateway(preferences, secrets, coordinator)
    }

    private fun fixture(client: AiClient): C1bFixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
        val conversations = C1bConversationRepository()
        val events = C1bEventRepository()
        val mutations = mutableListOf<ScheduleMutation>()
        val eventService = EventService(
            repository = events,
            clock = FIXED_CLOCK,
            uidGenerator = SyncUidGenerator {
                (events.writeCalls + 1).toString(16).padStart(32, '0')
            },
            mutationSink = ScheduleMutationSink(mutations::add)
        )
        val coordinator = AiCoordinator(
            scope,
            AiCoordinatorDependencies(
                client,
                conversations,
                AiMessageBudgeter(),
                AiResponseParser(),
                AiOperationExecutor(eventService),
                FIXED_CLOCK,
                AiContextProvider(FIXED_CLOCK, events) { FIXED_CLOCK.zone }
            )
        ).also(AiCoordinator::onAppForegrounded)
        return C1bFixture(coordinator, conversations, events, mutations)
    }

    private fun configuredState() = AppPreferencesState.DEFAULT.copy(
        ai = AppPreferencesState.DEFAULT.ai.copy(
            endpoint = "https://example.invalid",
            model = "model-test"
        )
    )

    private fun conversation(id: String) = Conversation(id, "test", FIXED_CLOCK.instant(), 0)

    private fun testKey() = "not-a-real-key".toCharArray()

    private suspend fun waitUntil(predicate: () -> Boolean) {
        withTimeout(3_000) {
            while (!predicate()) delay(10)
        }
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(
            Instant.parse("2026-08-09T03:00:00Z"),
            ZoneOffset.UTC
        )
    }
}

private data class C1bFixture(
    val coordinator: AiCoordinator,
    val conversations: C1bConversationRepository,
    val events: C1bEventRepository,
    val mutations: MutableList<ScheduleMutation>
)

private class RecordingC1bSecretStore(
    private val value: CharArray?,
    private val failure: RuntimeException? = null
) : SecretStore {
    var getCalls = 0
    var lastReturned: CharArray? = null
    val returnedValues = mutableListOf<CharArray?>()

    override suspend fun put(alias: SecretAlias, value: CharArray) = error("not used")

    override suspend fun get(alias: SecretAlias): CharArray? {
        getCalls += 1
        failure?.let { throw it }
        return value?.copyOf()?.also {
            lastReturned = it
            returnedValues += it
        }.also { if (it == null) returnedValues += null }
    }

    override suspend fun delete(alias: SecretAlias) = Unit
}

private class FailingC1bAiClient(private val failure: RuntimeException) : AiClient {
    override suspend fun fetchModels(settings: AiSettings, apiKey: CharArray) = error("not used")

    override suspend fun complete(
        settings: AiSettings,
        apiKey: CharArray,
        messages: List<AiRequestMessage>
    ): AiCompletion = throw failure

    override fun cancelInFlight() = Unit
}

private class BlockingC1bAiClient : AiClient {
    val completion = CompletableDeferred<AiCompletion>()
    val completeCalls = AtomicInteger()
    val cancelCalls = AtomicInteger()

    override suspend fun fetchModels(settings: AiSettings, apiKey: CharArray) = error("not used")

    override suspend fun complete(
        settings: AiSettings,
        apiKey: CharArray,
        messages: List<AiRequestMessage>
    ): AiCompletion {
        completeCalls.incrementAndGet()
        return completion.await()
    }

    override fun cancelInFlight() {
        cancelCalls.incrementAndGet()
    }
}

private class ImmediateC1bAiClient(private val value: AiCompletion) : AiClient {
    override suspend fun fetchModels(settings: AiSettings, apiKey: CharArray) = error("not used")

    override suspend fun complete(
        settings: AiSettings,
        apiKey: CharArray,
        messages: List<AiRequestMessage>
    ): AiCompletion = value

    override fun cancelInFlight() = Unit
}

private class C1bConversationRepository : ConversationRepository {
    val conversations = linkedMapOf<String, Conversation>()
    val messages = linkedMapOf<String, MutableList<Message>>()
    private var nextMessageId = 1L

    override suspend fun create(conversation: Conversation): Conversation = conversation.also {
        conversations[it.id] = it
    }

    override suspend fun findConversation(id: String): Conversation? = conversations[id]

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
        messages.getOrPut(conversationId, ::mutableListOf).add(message)
        conversations[conversationId] = current.copy(tokenCount = current.tokenCount + tokenDelta)
        return message
    }

    override suspend fun rename(id: String, title: String): Conversation? = null

    override suspend fun clearMessagesAndResetTokens(id: String): Boolean = false

    override suspend fun delete(id: String): Boolean = false
}

private class C1bEventRepository :
    EventRepository,
    VisibleScheduleSource {
    private val values = linkedMapOf<Long, Event>()
    var writeCalls = 0

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

    override suspend fun visibleEvents(): List<Event> = emptyList()
}

private fun reply(content: String) = AiCompletion(
    content,
    "",
    AiCompletionUsage(1, 1, 2)
)
