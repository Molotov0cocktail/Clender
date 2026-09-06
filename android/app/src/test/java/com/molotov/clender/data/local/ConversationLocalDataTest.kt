package com.molotov.clender.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.MessageRole
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ConversationLocalDataTest {
    private lateinit var database: ClenderDatabase
    private lateinit var repository: RoomConversationRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClenderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomConversationRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createAppendAndTokenUpdateAreTransactionalAndOrdered() = runBlocking {
        val conversation = Conversation(
            id = "conversation-1",
            title = "标题",
            createdAt = Instant.parse("2026-08-07T00:00:00Z"),
            tokenCount = 0
        )
        repository.create(conversation)

        val second = repository.appendMessageAndIncrementTokens(
            conversationId = conversation.id,
            role = MessageRole.ASSISTANT,
            content = "第二条",
            timestamp = Instant.parse("2026-08-07T02:00:00Z"),
            tokenDelta = 7
        )
        val first = repository.appendMessageAndIncrementTokens(
            conversationId = conversation.id,
            role = MessageRole.USER,
            content = "第一条",
            timestamp = Instant.parse("2026-08-07T01:00:00Z"),
            tokenDelta = 3
        )

        assertTrue(first.id > 0L)
        assertTrue(second.id > 0L)
        assertEquals(10, repository.findConversation(conversation.id)?.tokenCount)
        assertEquals(
            listOf("第一条", "第二条"),
            repository.observeMessages(conversation.id).first().map {
                it.content
            }
        )
    }

    @Test
    fun appendToMissingConversationRollsBackInsertedMessage() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.appendMessageAndIncrementTokens(
                    conversationId = "missing",
                    role = MessageRole.USER,
                    content = "must not survive",
                    timestamp = Instant.parse("2026-08-07T01:00:00Z"),
                    tokenDelta = 1
                )
            }
        }
        runBlocking {
            assertTrue(repository.observeMessages("missing").first().isEmpty())
        }
    }

    @Test
    fun deleteConversationCascadesMessages() = runBlocking {
        val conversation = Conversation("conversation-2", "title", Instant.EPOCH, 0)
        repository.create(conversation)
        repository.appendMessageAndIncrementTokens(
            conversation.id,
            MessageRole.THINK,
            "private reasoning",
            Instant.EPOCH,
            1
        )

        assertTrue(repository.delete(conversation.id))
        assertTrue(repository.observeMessages(conversation.id).first().isEmpty())
        assertEquals(null, repository.findConversation(conversation.id))
    }

    @Test
    fun invalidTokenDeltaDoesNotMutateConversationOrMessages() = runBlocking {
        val conversation = Conversation("conversation-3", "title", Instant.EPOCH, 4)
        repository.create(conversation)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.appendMessageAndIncrementTokens(
                    conversation.id,
                    MessageRole.USER,
                    "invalid",
                    Instant.EPOCH,
                    -5
                )
            }
        }
        assertEquals(4, repository.findConversation(conversation.id)?.tokenCount)
        assertTrue(repository.observeMessages(conversation.id).first().isEmpty())
    }

    @Test
    fun timestampsRoundTripAtMicrosecondsAndRejectOutOfRangeYear() = runBlocking {
        val created = Instant.parse("2026-08-07T00:00:00.123456789Z")
        val stored = repository.create(Conversation("conversation-4", "title", created, 0))
        assertEquals(Instant.parse("2026-08-07T00:00:00.123456Z"), stored.createdAt)
        assertEquals(stored, repository.findConversation(stored.id))

        val message = repository.appendMessageAndIncrementTokens(
            stored.id,
            MessageRole.USER,
            "content",
            Instant.parse("2026-08-07T01:00:00.987654321Z"),
            1
        )
        assertEquals(Instant.parse("2026-08-07T01:00:00.987654Z"), message.timestamp)
        assertEquals(message, repository.observeMessages(stored.id).first().single())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.create(
                    Conversation(
                        "out-of-range",
                        "title",
                        Instant.parse("+10000-01-01T00:00:00Z"),
                        0
                    )
                )
            }
        }
        assertEquals(null, repository.findConversation("out-of-range"))
    }

    @Test
    fun tokenUpdateFailureRollsBackInsertedMessage() = runBlocking {
        val conversation = Conversation("conversation-5", "title", Instant.EPOCH, 2)
        repository.create(conversation)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_token_update BEFORE UPDATE OF token_count ON conversations
            WHEN OLD.id = '${conversation.id}'
            BEGIN SELECT RAISE(ABORT, 'forced token update failure'); END
            """.trimIndent()
        )

        assertThrows(SQLiteException::class.java) {
            runBlocking {
                repository.appendMessageAndIncrementTokens(
                    conversation.id,
                    MessageRole.USER,
                    "must roll back",
                    Instant.EPOCH,
                    1
                )
            }
        }
        assertEquals(2, repository.findConversation(conversation.id)?.tokenCount)
        assertTrue(repository.observeMessages(conversation.id).first().isEmpty())
    }
}
