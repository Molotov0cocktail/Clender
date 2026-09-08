package com.molotov.clender.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.MessageRole
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ConversationContextUsageTest {
    private lateinit var database: ClenderDatabase
    private lateinit var repository: RoomConversationRepository

    @Before
    fun start() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ClenderDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = RoomConversationRepository(database)
        repository.create(Conversation("a", "Synthetic", Instant.EPOCH, 20_000))
        Unit
    }

    @After
    fun close() = database.close()

    @Test
    fun legacyConstructorsRemainUnknownAndNewSnapshotsRoundTrip() = runBlocking {
        assertNull(repository.findConversation("a")?.lastInputTokenEstimate)
        assertNull(repository.findConversation("a")?.lastContextWindow)
        val complete = Conversation("b", "Other", Instant.EPOCH, 99, 128, 4096)
        repository.create(complete)
        assertEquals(complete, repository.findConversation("b"))
    }

    @Test
    fun latestBudgetUpdatesAtomicallyWithoutReplacingUsageOrTitle() = runBlocking {
        assertTrue(repository.recordContextUsage("a", 300, 4096))
        assertTrue(repository.recordContextUsage("a", 700, 8192))
        val stored = requireNotNull(repository.findConversation("a"))
        assertEquals(700, stored.lastInputTokenEstimate)
        assertEquals(8192, stored.lastContextWindow)
        assertEquals(20_000, stored.tokenCount)
        assertEquals("Synthetic", stored.title)
        assertEquals(stored, repository.listConversations().single())
    }

    @Test
    fun invalidPairsCannotOverwriteLastValidSnapshot() = runBlocking {
        assertTrue(repository.recordContextUsage("a", 300, 4096))
        listOf(-1 to 4096, 1 to 0, 4097 to 4096).forEach { (input, limit) ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.recordContextUsage("a", input, limit) }
            }
        }
        assertEquals(300, repository.findConversation("a")?.lastInputTokenEstimate)
        assertEquals(4096, repository.findConversation("a")?.lastContextWindow)
    }

    @Test
    fun transactionRollbackPreservesBothBudgetFields() = runBlocking {
        assertTrue(repository.recordContextUsage("a", 300, 4096))
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                database.withTransaction {
                    assertTrue(repository.recordContextUsage("a", 700, 8192))
                    error("synthetic rollback")
                }
            }
        }
        assertEquals(300, repository.findConversation("a")?.lastInputTokenEstimate)
        assertEquals(4096, repository.findConversation("a")?.lastContextWindow)
    }

    @Test
    fun renameAndMessageUsageKeepSnapshotButClearAndDeleteRemoveIt() = runBlocking {
        assertTrue(repository.recordContextUsage("a", 300, 4096))
        repository.rename("a", "Renamed")
        repository.appendMessageAndIncrementTokens("a", MessageRole.USER, "合成", Instant.EPOCH, 5)
        assertEquals(300, repository.findConversation("a")?.lastInputTokenEstimate)
        assertEquals(20_005, repository.findConversation("a")?.tokenCount)
        assertTrue(repository.clearMessagesAndResetTokens("a"))
        assertNull(repository.findConversation("a")?.lastInputTokenEstimate)
        assertNull(repository.findConversation("a")?.lastContextWindow)
        assertTrue(repository.delete("a"))
        assertFalse(repository.recordContextUsage("a", 10, 4096))
        assertNull(repository.findConversation("a"))
    }
}
