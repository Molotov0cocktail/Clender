package com.molotov.clender.app.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.core.model.Conversation
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
import com.molotov.clender.domain.event.ScheduleMutationSink
import com.molotov.clender.domain.event.SyncUidGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AiContextUsageIntegrationTest {
    private lateinit var database: ClenderDatabase
    private lateinit var conversations: RoomConversationRepository
    private lateinit var coordinator: AiCoordinator
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.Default)
    private val client = ContextUsageClient()
    private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    private val contextEntered = Channel<Unit>(Channel.UNLIMITED)
    private var contextPause: CompletableDeferred<Unit>? = null

    @Before
    fun start() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ClenderDatabase::class.java
        ).allowMainThreadQueries().build()
        conversations = RoomConversationRepository(database)
        conversations.create(Conversation("a", "Synthetic A", Instant.EPOCH, 20_000))
        conversations.create(Conversation("b", "Synthetic B", Instant.EPOCH, 100))
        val events = EventService(
            RoomEventRepository(database),
            clock,
            SyncUidGenerator { "1".repeat(32) },
            ScheduleMutationSink { }
        )
        val context = AiContextProvider(
            clock,
            VisibleScheduleSource {
                contextPause?.let { pause ->
                    contextEntered.send(Unit)
                    withContext(NonCancellable) { pause.await() }
                }
                emptyList()
            }
        )
        coordinator = AiCoordinator(
            scope,
            AiCoordinatorDependencies(
                client,
                conversations,
                AiMessageBudgeter(),
                AiResponseParser(),
                AiOperationExecutor(events),
                clock,
                context
            )
        ).also(AiCoordinator::onAppForegrounded)
    }

    @After
    fun close() = runBlocking {
        contextPause?.complete(Unit)
        owner.cancelAndJoin()
        database.close()
    }

    @Test
    fun actualInputEstimateIsPublishedAndPersistedSeparatelyFromCumulativeUsage() = runBlocking {
        assertNull(coordinator.contextUsage.value)
        val sent = submit("a", settings())
        val expected = AiMessageBudgeter().countTokens(sent)
        assertEquals(AiContextUsage("a", expected, 8192), coordinator.contextUsage.value)
        assertEquals(expected, conversations.findConversation("a")?.lastInputTokenEstimate)
        assertEquals(8192, conversations.findConversation("a")?.lastContextWindow)
        assertEquals(20_000, conversations.findConversation("a")?.tokenCount)
        finish()
        assertEquals(expected, coordinator.contextUsage.value?.inputTokens)
        assertEquals(29_000, conversations.findConversation("a")?.tokenCount)
    }

    @Test
    fun conversationSwitchPersistsIndependentMostRecentModelLimits() = runBlocking {
        val first = AiMessageBudgeter().countTokens(submit("a", settings()))
        finish()
        val second = AiMessageBudgeter().countTokens(
            submit("b", settings().copy(contextWindow = 16_384))
        )
        assertEquals(AiContextUsage("b", second, 16_384), coordinator.contextUsage.value)
        assertEquals(first, conversations.findConversation("a")?.lastInputTokenEstimate)
        assertEquals(8192, conversations.findConversation("a")?.lastContextWindow)
        assertEquals(second, conversations.findConversation("b")?.lastInputTokenEstimate)
        assertEquals(16_384, conversations.findConversation("b")?.lastContextWindow)
    }

    @Test
    fun invalidNextBudgetKeepsLastValidConversationSnapshot() = runBlocking {
        val first = AiMessageBudgeter().countTokens(submit("a", settings()))
        finish()
        assertTrue(
            coordinator.submit(
                "a",
                "合成",
                settings().copy(contextWindow = 512, maxOutputTokens = 400),
                "synthetic-key".toCharArray()
            )
        )
        withTimeout(5_000) { coordinator.state.first { it is AiCoordinatorState.Failed } }
        assertEquals(AiContextUsage("a", first, 8192), coordinator.contextUsage.value)
        assertEquals(first, conversations.findConversation("a")?.lastInputTokenEstimate)
        assertTrue(client.requests.tryReceive().isFailure)
    }

    @Test
    fun cancelledLateContextCannotOverwriteSnapshotOrSendAnotherRequest() = runBlocking {
        val first = AiMessageBudgeter().countTokens(submit("a", settings()))
        finish()
        contextPause = CompletableDeferred()
        assertTrue(coordinator.submit("b", "合成", settings(), "synthetic-key".toCharArray()))
        withTimeout(5_000) { contextEntered.receive() }
        coordinator.onAppBackgrounded()
        contextPause?.complete(Unit)
        withTimeout(5_000) { owner.children.toList().forEach { it.join() } }
        assertEquals(AiContextUsage("a", first, 8192), coordinator.contextUsage.value)
        assertNull(conversations.findConversation("b")?.lastInputTokenEstimate)
        assertTrue(client.requests.tryReceive().isFailure)
    }

    private suspend fun submit(id: String, options: AiSettings): List<AiRequestMessage> {
        assertTrue(coordinator.submit(id, "合成请求", options, "synthetic-key".toCharArray()))
        return withTimeout(5_000) { client.requests.receive() }
    }

    private suspend fun finish() {
        client.responses.send(
            AiCompletion(
                """{"operations":[{"action":"reply","message":"合成回复"}]}""",
                "",
                AiCompletionUsage(4000, 5000, 9000)
            )
        )
        withTimeout(5_000) { coordinator.state.first { it is AiCoordinatorState.Idle } }
        // Idle is published before the request coroutine returns; this fixture starts
        // a separate request only after its predecessor has released its active job.
        withTimeout(5_000) { owner.children.toList().forEach { it.join() } }
    }

    private fun settings() = AiSettings(
        "https://example.invalid",
        "synthetic-model",
        0.7,
        512,
        8192,
        true,
        ThinkingEffort.HIGH,
        "",
        ""
    )
}

private class ContextUsageClient : AiClient {
    val requests = Channel<List<AiRequestMessage>>(Channel.UNLIMITED)
    val responses = Channel<AiCompletion>(Channel.UNLIMITED)
    override suspend fun fetchModels(settings: AiSettings, apiKey: CharArray): List<String> =
        error("Not used")

    override suspend fun complete(
        settings: AiSettings,
        apiKey: CharArray,
        messages: List<AiRequestMessage>
    ): AiCompletion {
        apiKey.fill('\u0000')
        requests.send(messages)
        return responses.receive()
    }

    override fun cancelInFlight() = Unit
}
