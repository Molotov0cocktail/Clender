package com.molotov.clender.app.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import com.molotov.clender.data.network.ai.AiClient
import com.molotov.clender.data.network.ai.AiCompletion
import com.molotov.clender.data.network.ai.AiCompletionUsage
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
import com.molotov.clender.domain.event.ScheduleMutationSink
import com.molotov.clender.domain.event.SyncUidGenerator
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AiOperationGateC2aContractTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        scopes.forEach(CoroutineScope::cancel)
        files.forEach(File::delete)
    }

    @Test
    fun leaseIsExclusiveIdempotentAndGateCanBeClosed() {
        val gate = AiOperationGate()
        val first = requireNotNull(gate.tryAcquire())

        assertNull(gate.tryAcquire())
        first.close()
        first.close()
        requireNotNull(gate.tryAcquire()).close()

        gate.close()
        assertNull(gate.tryAcquire())
    }

    @Test
    fun chatBusyIsDecidedBeforeSecretReadAndUserAppend() = runBlocking {
        val gate = AiOperationGate()
        val heldByModels = requireNotNull(gate.tryAcquire())
        val fixture = fixture(gate)
        fixture.conversations.create(conversation("chat"))

        assertEquals(
            AiSubmissionDecision.BUSY,
            fixture.gateway.submit("chat", "must-not-append")
        )
        assertEquals(0, fixture.secrets.getCalls)
        assertTrue(fixture.conversations.messages.getValue("chat").isEmpty())
        assertEquals(0, fixture.client.completeCalls)
        heldByModels.close()
    }

    @Test
    fun acceptedChatHoldsLeaseUntilTerminalAndUsesCapturedSettings() = runBlocking {
        val gate = AiOperationGate()
        val fixture = fixture(gate)
        fixture.conversations.create(conversation("chat"))

        assertEquals(AiSubmissionDecision.ACCEPTED, fixture.gateway.submit("chat", "hello"))
        fixture.client.started.await()
        assertNull(gate.tryAcquire())

        fixture.preferences.save(
            configuredState().copy(
                ai = configuredState().ai.copy(
                    endpoint = "https://later.invalid",
                    model = "later"
                )
            )
        )
        assertEquals("https://example.invalid", fixture.client.capturedSettings?.endpoint)
        assertEquals("model-test", fixture.client.capturedSettings?.model)

        fixture.client.completion.complete(reply("done"))
        waitUntil { fixture.coordinator.state.value == AiCoordinatorState.Idle }
        requireNotNull(gate.tryAcquire()).close()
    }

    @Test
    fun backgroundCancellationAndScopeCloseReleaseLeaseAndWipeKeys() = runBlocking {
        val backgroundGate = AiOperationGate()
        val background = fixture(backgroundGate)
        background.conversations.create(conversation("background"))
        assertEquals(
            AiSubmissionDecision.ACCEPTED,
            background.gateway.submit("background", "hello")
        )
        background.client.started.await()
        background.coordinator.onAppBackgrounded()
        waitUntil { background.coordinator.state.value == AiCoordinatorState.Cancelled }
        waitUntil { backgroundGate.tryAcquire()?.also { it.close() } != null }
        assertTrue(background.secrets.returned.all { it == '\u0000' })

        val closeGate = AiOperationGate()
        val closing = fixture(closeGate)
        closing.conversations.create(conversation("close"))
        assertEquals(AiSubmissionDecision.ACCEPTED, closing.gateway.submit("close", "hello"))
        closing.client.started.await()
        closing.scope.cancel()
        waitUntil { closeGate.tryAcquire()?.also { it.close() } != null }
        assertTrue(closing.secrets.returned.all { it == '\u0000' })
    }

    private suspend fun fixture(gate: AiOperationGate): GateFixture {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "datastore/c2a-gate-${UUID.randomUUID()}.preferences_pb")
        files += file
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        val preferences = DataStoreAppPreferences(
            PreferenceDataStoreFactory.create(scope = dataStoreScope) { file }
        )
        preferences.save(configuredState())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
        val conversations = GateConversationRepository()
        val events = GateEventRepository()
        val client = BlockingGateAiClient()
        val coordinator = AiCoordinator(
            scope,
            AiCoordinatorDependencies(
                client = client,
                conversations = conversations,
                budgeter = AiMessageBudgeter(),
                parser = AiResponseParser(),
                executor = AiOperationExecutor(
                    EventService(
                        repository = events,
                        clock = CLOCK,
                        uidGenerator = SyncUidGenerator { "1".padStart(32, '0') },
                        mutationSink = ScheduleMutationSink { }
                    )
                ),
                clock = CLOCK,
                contextProvider = AiContextProvider(CLOCK, events) { CLOCK.zone }
            )
        ).also(AiCoordinator::onAppForegrounded)
        val secrets = GateSecretStore()
        val gateway = ConfiguredAiSubmissionGateway(preferences, secrets, coordinator, gate)
        return GateFixture(
            scope,
            preferences,
            gateway,
            coordinator,
            conversations,
            secrets,
            client
        )
    }

    private fun configuredState() = AppPreferencesState.DEFAULT.copy(
        ai = AppPreferencesState.DEFAULT.ai.copy(
            endpoint = "https://example.invalid",
            model = "model-test"
        )
    )

    private fun conversation(id: String) = Conversation(id, "test", CLOCK.instant(), 0)

    private suspend fun waitUntil(predicate: () -> Boolean) {
        withTimeout(3_000) {
            while (!predicate()) delay(10)
        }
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC)
    }
}

private data class GateFixture(
    val scope: CoroutineScope,
    val preferences: DataStoreAppPreferences,
    val gateway: ConfiguredAiSubmissionGateway,
    val coordinator: AiCoordinator,
    val conversations: GateConversationRepository,
    val secrets: GateSecretStore,
    val client: BlockingGateAiClient
)

private class GateSecretStore : SecretStore {
    var getCalls = 0
    lateinit var returned: CharArray

    override suspend fun put(alias: SecretAlias, value: CharArray) = error("not used")

    override suspend fun get(alias: SecretAlias): CharArray {
        getCalls += 1
        return "placeholder-chat-value".toCharArray().also { returned = it }
    }

    override suspend fun delete(alias: SecretAlias) = Unit
}

private class BlockingGateAiClient : AiClient {
    val started = CompletableDeferred<Unit>()
    val completion = CompletableDeferred<AiCompletion>()
    var completeCalls = 0
    var capturedSettings: AiSettings? = null

    override suspend fun fetchModels(settings: AiSettings, apiKey: CharArray) = error("not used")

    override suspend fun complete(
        settings: AiSettings,
        apiKey: CharArray,
        messages: List<AiRequestMessage>
    ): AiCompletion {
        completeCalls += 1
        capturedSettings = settings
        apiKey.fill('\u0000')
        started.complete(Unit)
        return completion.await()
    }

    override fun cancelInFlight() {
        completion.cancel()
    }
}

private class GateConversationRepository : ConversationRepository {
    val conversations = linkedMapOf<String, Conversation>()
    val messages = linkedMapOf<String, MutableList<Message>>()
    private var nextMessageId = 1L

    override suspend fun create(conversation: Conversation): Conversation {
        conversations[conversation.id] = conversation
        messages[conversation.id] = mutableListOf()
        return conversation
    }

    override suspend fun listConversations(): List<Conversation> = conversations.values.toList()

    override suspend fun findConversation(id: String): Conversation? = conversations[id]

    override suspend fun rename(id: String, title: String): Conversation? = error("not used")

    override suspend fun clearMessagesAndResetTokens(id: String): Boolean = error("not used")

    override suspend fun delete(id: String): Boolean = error("not used")

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        flowOf(messages[conversationId].orEmpty())

    override suspend fun appendMessageAndIncrementTokens(
        conversationId: String,
        role: MessageRole,
        content: String,
        timestamp: Instant,
        tokenDelta: Int
    ): Message {
        val value = Message(nextMessageId++, conversationId, role, content, timestamp)
        messages.getValue(conversationId) += value
        val conversation = conversations.getValue(conversationId)
        conversations[conversationId] = conversation.copy(
            tokenCount = conversation.tokenCount + tokenDelta
        )
        return value
    }
}

private class GateEventRepository :
    EventRepository,
    VisibleScheduleSource {
    override suspend fun findById(id: Long): Event? = null
    override suspend fun insert(event: Event): Event = error("not used")
    override suspend fun update(event: Event): Event = error("not used")
    override fun observeRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> =
        flowOf(emptyList())
    override suspend fun visibleEvents(): List<Event> = emptyList()
}

private fun reply(content: String) = AiCompletion(
    content = content,
    reasoningContent = "",
    usage = AiCompletionUsage(1, 1, 2)
)
