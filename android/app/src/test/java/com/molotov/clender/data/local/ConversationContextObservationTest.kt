package com.molotov.clender.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import java.time.Instant
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ConversationContextObservationTest {
    private lateinit var database: ClenderDatabase
    private lateinit var repository: RoomConversationRepository

    @Before
    fun start() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ClenderDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = RoomConversationRepository(database)
        repository.create(Conversation("a", "Synthetic", Instant.EPOCH, 0))
        Unit
    }

    @After
    fun close() = database.close()

    @Test
    fun budgetOnlyWritesRefreshAnAlreadyObservedUserMessageList() = verifyRefresh(true)

    @Test
    fun budgetOnlyWritesRefreshAnEmptyMessageListAndClearRestoresUnknown() = verifyRefresh(false)

    private fun verifyRefresh(withMessage: Boolean) = runBlocking {
        val snapshots = Channel<Snapshot>(Channel.UNLIMITED)
        val observer = launch {
            repository.observeMessages("a").collect { messages ->
                snapshots.send(Snapshot(messages, requireNotNull(repository.findConversation("a"))))
            }
        }
        try {
            snapshots.awaitSnapshot { messages.isEmpty() }
            val expectedMessages = if (withMessage) {
                val message = repository.appendMessageAndIncrementTokens(
                    "a",
                    MessageRole.USER,
                    "合成请求",
                    Instant.EPOCH,
                    7
                )
                snapshots.awaitSnapshot { messages == listOf(message) }
                listOf(message)
            } else {
                emptyList()
            }
            repository.recordContextUsage("a", 300, 4096)
            val refreshed = snapshots.awaitSnapshot {
                conversation.lastInputTokenEstimate == 300 &&
                    conversation.lastContextWindow == 4096
            }
            assertEquals(expectedMessages, refreshed.messages)
            repository.clearMessagesAndResetTokens("a")
            val cleared = snapshots.awaitSnapshot {
                messages.isEmpty() && conversation.lastInputTokenEstimate == null &&
                    conversation.lastContextWindow == null
            }
            assertEquals(0, cleared.conversation.tokenCount)
        } finally {
            observer.cancelAndJoin()
            snapshots.close()
        }
    }

    private suspend fun Channel<Snapshot>.awaitSnapshot(
        predicate: Snapshot.() -> Boolean
    ): Snapshot = withTimeout(5_000) {
        var snapshot = receive()
        while (!snapshot.predicate()) snapshot = receive()
        snapshot
    }

    private data class Snapshot(val messages: List<Message>, val conversation: Conversation)
}
