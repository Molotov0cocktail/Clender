package com.molotov.clender.data.local.conversation

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.MessageRole
import com.molotov.clender.data.local.ClenderDatabase
import com.molotov.clender.data.local.RoomConversationRepository
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class RoomConversationManagementTest {
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
    fun listIsDeterministicAndRenameTrimsWithoutChangingIdentity(): Unit = runBlocking {
        repository.create(conversation("later", "后", "2026-08-09T02:00:00Z"))
        repository.create(conversation("b", "B", "2026-08-09T01:00:00Z"))
        repository.create(conversation("a", "A", "2026-08-09T01:00:00Z"))

        assertEquals(listOf("a", "b", "later"), repository.listConversations().map { it.id })
        val renamed = requireNotNull(repository.rename("a", "  新名称 🌏  "))
        assertEquals("新名称 🌏", renamed.title)
        assertEquals("a", renamed.id)
        assertEquals(null, repository.rename("missing", "name"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.rename("a", " \t") }
        }
    }

    @Test
    fun clearMessagesAndTokenResetCommitTogether() = runBlocking {
        val conversation = conversation("clear", "title", "2026-08-09T01:00:00Z")
        repository.create(conversation)
        repository.appendMessageAndIncrementTokens(
            conversation.id,
            MessageRole.USER,
            "message",
            conversation.createdAt,
            5
        )

        assertTrue(repository.clearMessagesAndResetTokens(conversation.id))

        assertTrue(repository.observeMessages(conversation.id).first().isEmpty())
        assertEquals(0, repository.findConversation(conversation.id)?.tokenCount)
        assertFalse(repository.clearMessagesAndResetTokens("missing"))
    }

    @Test
    fun tokenResetFailureRollsBackMessageDeletionInSameRoomTransaction() = runBlocking {
        val conversation = conversation("rollback", "title", "2026-08-09T01:00:00Z")
        repository.create(conversation)
        repository.appendMessageAndIncrementTokens(
            conversation.id,
            MessageRole.THINK,
            "must survive rollback",
            conversation.createdAt,
            7
        )
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_clear_token BEFORE UPDATE OF token_count ON conversations
            WHEN OLD.id = '${conversation.id}'
            BEGIN SELECT RAISE(ABORT, 'forced clear token failure'); END
            """.trimIndent()
        )

        assertThrows(SQLiteException::class.java) {
            runBlocking { repository.clearMessagesAndResetTokens(conversation.id) }
        }

        assertEquals(7, repository.findConversation(conversation.id)?.tokenCount)
        assertEquals(
            listOf("must survive rollback"),
            repository.observeMessages(conversation.id).first().map { it.content }
        )
    }

    private fun conversation(id: String, title: String, createdAt: String) = Conversation(
        id = id,
        title = title,
        createdAt = Instant.parse(createdAt),
        tokenCount = 0
    )
}
