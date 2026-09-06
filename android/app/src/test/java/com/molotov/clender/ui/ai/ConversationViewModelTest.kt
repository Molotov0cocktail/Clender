package com.molotov.clender.ui.ai

import android.os.Looper
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import com.molotov.clender.domain.conversation.ActiveConversationStore
import com.molotov.clender.domain.conversation.ConversationIdGenerator
import com.molotov.clender.domain.conversation.ConversationManager
import com.molotov.clender.domain.conversation.ConversationRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.ArrayDeque
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ConversationViewModelTest {
    @Test
    fun constructionIsInactiveAndPerformsZeroRepositoryOrStoreAccess() {
        val fixture = fixture()

        assertEquals(ConversationLoadStatus.INACTIVE, fixture.viewModel.state.value.loadStatus)
        assertEquals(0, fixture.repository.totalReads)
        assertEquals(0, fixture.store.readCollections)
        assertTrue(fixture.repository.observedIds.isEmpty())
    }

    @Test
    fun activateEmptyRepositoryCreatesExactlyOneDefaultAndIsIdempotent() {
        val fixture = fixture(ids = listOf(HEX_A, HEX_B))

        fixture.viewModel.activate()
        idleMain()
        fixture.viewModel.activate()
        idleMain()

        val state = fixture.viewModel.state.value
        assertEquals(ConversationLoadStatus.READY, state.loadStatus)
        assertEquals(listOf(HEX_A), state.conversations.map { it.id })
        assertEquals("新对话", state.activeConversation?.title)
        assertEquals(1, fixture.repository.createCalls)
        assertEquals(listOf(HEX_A), fixture.store.writes)
        assertEquals(listOf(HEX_A), fixture.repository.observedIds)
    }

    @Test
    fun activateReusesPreferredOrFallsBackToFirstRepositoryOrder() {
        val first = conversation(HEX_A, "first", "2026-08-31T00:00:00Z")
        val preferred = conversation(HEX_B, "preferred", "2026-08-31T01:00:00Z")
        val preferredFixture = fixture(listOf(first, preferred), preferredId = HEX_B)

        preferredFixture.viewModel.activate()
        idleMain()

        assertEquals(HEX_B, preferredFixture.viewModel.state.value.activeConversation?.id)
        assertEquals(0, preferredFixture.repository.createCalls)
        assertEquals(listOf(HEX_B), preferredFixture.store.writes)

        val missingFixture = fixture(listOf(first, preferred), preferredId = HEX_C)
        missingFixture.viewModel.activate()
        idleMain()

        assertEquals(HEX_A, missingFixture.viewModel.state.value.activeConversation?.id)
        assertEquals(listOf(HEX_A), missingFixture.store.writes)
        assertEquals(
            listOf(HEX_A, HEX_B),
            missingFixture.viewModel.state.value.conversations.map { it.id }
        )
    }

    @Test
    fun initializationFailureIsBoundedAndRetryRecoversWithoutLeakingThrowable() {
        val secret = "sqlite /private/path hidden conversation"
        val fixture = fixture().also {
            it.repository.listFailure = IllegalStateException(secret)
        }

        fixture.viewModel.activate()
        idleMain()

        assertEquals(ConversationLoadStatus.ERROR, fixture.viewModel.state.value.loadStatus)
        assertEquals(
            ConversationErrorCode.INITIALIZE_FAILED,
            fixture.viewModel.state.value.errorCode
        )
        assertFalse(fixture.viewModel.state.value.toString().contains(secret))

        fixture.repository.listFailure = null
        fixture.viewModel.retry()
        idleMain()

        assertEquals(ConversationLoadStatus.READY, fixture.viewModel.state.value.loadStatus)
        assertNull(fixture.viewModel.state.value.errorCode)
        assertEquals(1, fixture.repository.createCalls)
    }

    @Test
    fun selectingConversationUpdatesImmediatelyPersistsAndCancelsOldObservation() {
        val first = conversation(HEX_A, "first", "2026-08-31T00:00:00Z")
        val second = conversation(HEX_B, "second", "2026-08-31T01:00:00Z")
        val fixture = fixture(listOf(first, second), preferredId = HEX_A)
        fixture.viewModel.activate()
        idleMain()

        fixture.viewModel.selectConversation(HEX_B)

        assertEquals(HEX_B, fixture.viewModel.state.value.activeConversation?.id)
        idleMain()
        assertEquals(listOf(HEX_A, HEX_B), fixture.repository.observedIds)
        assertTrue(fixture.repository.cancelledIds.contains(HEX_A))
        assertEquals(HEX_B, fixture.store.writes.last())
        assertEquals(ConversationPane.MESSAGES, fixture.viewModel.state.value.compactPane)
    }

    @Test
    fun staleOldFlowCannotOverwriteSortedMessagesForNewSelection() {
        val first = conversation(HEX_A, "first", "2026-08-31T00:00:00Z")
        val second = conversation(HEX_B, "second", "2026-08-31T01:00:00Z")
        val fixture = fixture(listOf(first, second), preferredId = HEX_A)
        fixture.viewModel.activate()
        idleMain()
        fixture.viewModel.selectConversation(HEX_B)
        idleMain()

        fixture.repository.emit(
            HEX_A,
            listOf(message(91, HEX_A, "stale", "2026-08-31T03:00:00Z"))
        )
        fixture.repository.emit(
            HEX_B,
            listOf(
                message(3, HEX_B, "later-id", "2026-08-31T02:00:00Z"),
                message(9, HEX_B, "later-time", "2026-08-31T03:00:00Z"),
                message(2, HEX_B, "first", "2026-08-31T01:00:00Z"),
                message(1, HEX_B, "earlier-id", "2026-08-31T02:00:00Z")
            )
        )
        idleMain()

        assertEquals(
            listOf("first", "earlier-id", "later-id", "later-time"),
            fixture.viewModel.state.value.messages.map { it.content }
        )
        assertFalse(fixture.viewModel.state.value.toString().contains("stale"))
    }

    @Test
    fun messageFlowFailureIsBoundedAndRetryStartsFreshObservation() {
        val existing = conversation(HEX_A, "first", "2026-08-31T00:00:00Z")
        val fixture = fixture(listOf(existing), preferredId = HEX_A)
        fixture.repository.flowFailures[HEX_A] = IllegalStateException("secret SQL body")

        fixture.viewModel.activate()
        idleMain()

        assertEquals(ConversationLoadStatus.ERROR, fixture.viewModel.state.value.loadStatus)
        assertEquals(ConversationErrorCode.MESSAGES_FAILED, fixture.viewModel.state.value.errorCode)
        assertFalse(fixture.viewModel.state.value.toString().contains("secret SQL body"))

        fixture.repository.flowFailures.remove(HEX_A)
        fixture.viewModel.retry()
        idleMain()
        fixture.repository.emit(HEX_A, emptyList())
        idleMain()

        assertEquals(ConversationLoadStatus.READY, fixture.viewModel.state.value.loadStatus)
        assertEquals(2, fixture.repository.observedIds.count { it == HEX_A })
    }

    @Test
    fun createIsGuardedBecomesActiveAndUsesUniqueLowerHexId() {
        val fixture = fixture(ids = listOf(HEX_A, HEX_B))
        fixture.viewModel.activate()
        idleMain()

        fixture.viewModel.createConversation()
        fixture.viewModel.createConversation()
        idleMain()

        assertEquals(2, fixture.repository.createCalls)
        assertEquals(HEX_B, fixture.viewModel.state.value.activeConversation?.id)
        val activeId = requireNotNull(fixture.viewModel.state.value.activeConversation).id
        assertTrue(Regex("[0-9a-f]{32}").matches(activeId))
        assertEquals(HEX_B, fixture.store.writes.last())
    }

    @Test
    fun renameTrimsAndBlankRenamePerformsZeroWrite() {
        val existing = conversation(HEX_A, "old", "2026-08-31T00:00:00Z")
        val fixture = fixture(listOf(existing), preferredId = HEX_A)
        fixture.viewModel.activate()
        idleMain()

        fixture.viewModel.requestRename(HEX_A)
        fixture.viewModel.updateRenameTitle(" \t ")
        fixture.viewModel.confirmRename()
        idleMain()
        assertEquals(0, fixture.repository.renameCalls)
        assertTrue(fixture.viewModel.state.value.dialog is ConversationDialogState.Rename)

        fixture.viewModel.updateRenameTitle("  新标题 🌏  ")
        fixture.viewModel.confirmRename()
        fixture.viewModel.confirmRename()
        idleMain()

        assertEquals(1, fixture.repository.renameCalls)
        assertEquals("新标题 🌏", fixture.viewModel.state.value.activeConversation?.title)
        assertNull(fixture.viewModel.state.value.dialog)
    }

    @Test
    fun clearRequiresConfirmationCancelIsZeroWriteAndDoubleConfirmIsOneWrite() {
        val existing = conversation(HEX_A, "title", "2026-08-31T00:00:00Z", tokenCount = 8)
        val fixture = fixture(listOf(existing), preferredId = HEX_A)
        fixture.viewModel.activate()
        idleMain()

        fixture.viewModel.requestClear(HEX_A)
        assertEquals(
            ConversationDialogState.ConfirmClear(HEX_A),
            fixture.viewModel.state.value.dialog
        )
        fixture.viewModel.dismissDialog()
        assertEquals(0, fixture.repository.clearCalls)

        fixture.viewModel.requestClear(HEX_A)
        fixture.viewModel.confirmClear()
        fixture.viewModel.confirmClear()
        idleMain()

        assertEquals(1, fixture.repository.clearCalls)
        assertEquals(0, fixture.viewModel.state.value.activeConversation?.tokenCount)
        assertNull(fixture.viewModel.state.value.dialog)
    }

    @Test
    fun deletingInactiveKeepsActiveWhileDeletingActiveChoosesDeterministicNext() {
        val first = conversation(HEX_A, "first", "2026-08-31T00:00:00Z")
        val second = conversation(HEX_B, "second", "2026-08-31T01:00:00Z")
        val third = conversation(HEX_C, "third", "2026-08-31T02:00:00Z")
        val fixture = fixture(listOf(first, second, third), preferredId = HEX_B)
        fixture.viewModel.activate()
        idleMain()

        fixture.viewModel.requestDelete(HEX_C)
        fixture.viewModel.confirmDelete()
        idleMain()
        assertEquals(HEX_B, fixture.viewModel.state.value.activeConversation?.id)

        fixture.viewModel.requestDelete(HEX_B)
        fixture.viewModel.confirmDelete()
        fixture.viewModel.confirmDelete()
        idleMain()

        assertEquals(2, fixture.repository.deleteCalls)
        assertEquals(HEX_A, fixture.viewModel.state.value.activeConversation?.id)
        assertTrue(fixture.repository.cancelledIds.contains(HEX_B))
        assertEquals(HEX_A, fixture.store.writes.last())
    }

    @Test
    fun deletingLastConversationImmediatelyCreatesReplacementAndStopsOldFlow() {
        val existing = conversation(HEX_A, "only", "2026-08-31T00:00:00Z")
        val fixture = fixture(listOf(existing), preferredId = HEX_A, ids = listOf(HEX_B))
        fixture.viewModel.activate()
        idleMain()

        fixture.viewModel.requestDelete(HEX_A)
        fixture.viewModel.confirmDelete()
        idleMain()

        assertEquals(listOf(HEX_B), fixture.viewModel.state.value.conversations.map { it.id })
        assertEquals(HEX_B, fixture.viewModel.state.value.activeConversation?.id)
        assertTrue(fixture.repository.cancelledIds.contains(HEX_A))
        assertEquals(listOf(HEX_A, HEX_B), fixture.repository.observedIds)
    }

    @Test
    fun operationFailureIsRecoverableBoundedAndKeepsCurrentConversation() {
        val existing = conversation(HEX_A, "visible", "2026-08-31T00:00:00Z")
        val fixture = fixture(listOf(existing), preferredId = HEX_A)
        fixture.viewModel.activate()
        idleMain()
        fixture.repository.renameFailure = IllegalStateException("SQL /private/path visible")

        fixture.viewModel.requestRename(HEX_A)
        fixture.viewModel.updateRenameTitle("retained input")
        fixture.viewModel.confirmRename()
        idleMain()

        val state = fixture.viewModel.state.value
        assertEquals(ConversationErrorCode.OPERATION_FAILED, state.errorCode)
        assertEquals(ConversationOperation.IDLE, state.operation)
        assertEquals("visible", state.activeConversation?.title)
        assertTrue(state.dialog is ConversationDialogState.Rename)
        assertFalse(state.toString().contains("SQL /private/path"))
    }

    @Test
    fun compactPaneTransitionsAndUnknownSelectionDoNotTouchPersistenceOrMessages() {
        val existing = conversation(HEX_A, "visible", "2026-08-31T00:00:00Z")
        val fixture = fixture(listOf(existing), preferredId = HEX_A)
        fixture.viewModel.activate()
        idleMain()
        val writesBefore = fixture.store.writes.size

        fixture.viewModel.showConversationList()
        assertEquals(ConversationPane.LIST, fixture.viewModel.state.value.compactPane)
        fixture.viewModel.selectConversation(HEX_B)
        idleMain()

        assertEquals(HEX_A, fixture.viewModel.state.value.activeConversation?.id)
        assertEquals(writesBefore, fixture.store.writes.size)
        assertEquals(listOf(HEX_A), fixture.repository.observedIds)
        fixture.viewModel.showMessages()
        assertEquals(ConversationPane.MESSAGES, fixture.viewModel.state.value.compactPane)
    }

    @Test
    fun allOfflineManagementPathsAppendZeroMessages() {
        val existing = conversation(HEX_A, "visible", "2026-08-31T00:00:00Z")
        val fixture = fixture(listOf(existing), preferredId = HEX_A, ids = listOf(HEX_B))
        fixture.viewModel.activate()
        idleMain()
        fixture.viewModel.createConversation()
        idleMain()
        fixture.viewModel.requestRename(HEX_B)
        fixture.viewModel.updateRenameTitle("renamed")
        fixture.viewModel.confirmRename()
        idleMain()
        fixture.viewModel.requestClear(HEX_B)
        fixture.viewModel.confirmClear()
        idleMain()
        fixture.viewModel.requestDelete(HEX_A)
        fixture.viewModel.confirmDelete()
        idleMain()

        assertEquals(0, fixture.repository.appendCalls)
    }

    private fun fixture(
        conversations: List<Conversation> = emptyList(),
        preferredId: String? = null,
        ids: List<String> = listOf(HEX_A, HEX_B, HEX_C)
    ): Fixture {
        val repository = RecordingConversationRepository(conversations)
        val idQueue = ArrayDeque(ids)
        val manager = ConversationManager(
            repository = repository,
            clock = Clock.fixed(Instant.parse("2026-08-31T08:30:00.123456789Z"), ZoneOffset.UTC),
            idGenerator = ConversationIdGenerator { idQueue.removeFirst() },
            defaultTitle = "新对话"
        )
        val store = RecordingActiveConversationStore(preferredId)
        return Fixture(
            repository,
            store,
            ConversationViewModel(repository, manager, store)
        )
    }

    private fun idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private data class Fixture(
        val repository: RecordingConversationRepository,
        val store: RecordingActiveConversationStore,
        val viewModel: ConversationViewModel
    )

    private companion object {
        const val HEX_A = "0000000000000000000000000000000a"
        const val HEX_B = "0000000000000000000000000000000b"
        const val HEX_C = "0000000000000000000000000000000c"
    }
}

private class RecordingActiveConversationStore(initial: String?) : ActiveConversationStore {
    private val values = MutableStateFlow(initial)
    var readCollections = 0
    val writes = mutableListOf<String?>()

    override val activeConversationId: Flow<String?> = flow {
        readCollections += 1
        values.collect { emit(it) }
    }

    override suspend fun setActiveConversationId(id: String?) {
        writes += id
        values.value = id
    }
}

private class RecordingConversationRepository(initial: List<Conversation>) :
    ConversationRepository {
    private val conversations = initial.associateByTo(linkedMapOf()) { it.id }
    private val streams = mutableMapOf<String, MutableSharedFlow<List<Message>>>()
    val flowFailures = mutableMapOf<String, Throwable>()
    val observedIds = mutableListOf<String>()
    val cancelledIds = mutableListOf<String>()
    var listFailure: Throwable? = null
    var renameFailure: Throwable? = null
    var createCalls = 0
    var renameCalls = 0
    var clearCalls = 0
    var deleteCalls = 0
    var appendCalls = 0
    var totalReads = 0

    override suspend fun create(conversation: Conversation): Conversation {
        createCalls += 1
        conversations[conversation.id] = conversation
        return conversation
    }

    override suspend fun findConversation(id: String): Conversation? {
        totalReads += 1
        return conversations[id]
    }

    override suspend fun listConversations(): List<Conversation> {
        totalReads += 1
        listFailure?.let { throw it }
        return conversations.values.sortedWith(compareBy(Conversation::createdAt, Conversation::id))
    }

    override fun observeMessages(conversationId: String): Flow<List<Message>> = flow {
        observedIds += conversationId
        try {
            flowFailures[conversationId]?.let { throw it }
            emit(emptyList())
            streams.getOrPut(conversationId) {
                MutableSharedFlow(extraBufferCapacity = 16)
            }.asSharedFlow().collect { emit(it) }
        } finally {
            cancelledIds += conversationId
        }
    }

    fun emit(conversationId: String, messages: List<Message>) {
        streams.getOrPut(conversationId) {
            MutableSharedFlow(extraBufferCapacity = 16)
        }.tryEmit(messages)
    }

    override suspend fun appendMessageAndIncrementTokens(
        conversationId: String,
        role: MessageRole,
        content: String,
        timestamp: Instant,
        tokenDelta: Int
    ): Message {
        appendCalls += 1
        error("C1a must not append messages")
    }

    override suspend fun rename(id: String, title: String): Conversation? {
        renameCalls += 1
        renameFailure?.let { throw it }
        return conversations[id]?.copy(title = title)?.also { conversations[id] = it }
    }

    override suspend fun clearMessagesAndResetTokens(id: String): Boolean {
        clearCalls += 1
        val existing = conversations[id] ?: return false
        conversations[id] = existing.copy(tokenCount = 0)
        emit(id, emptyList())
        return true
    }

    override suspend fun delete(id: String): Boolean {
        deleteCalls += 1
        return conversations.remove(id) != null
    }
}

private fun conversation(id: String, title: String, createdAt: String, tokenCount: Int = 0) =
    Conversation(id, title, Instant.parse(createdAt), tokenCount)

private fun message(id: Long, conversationId: String, content: String, timestamp: String) = Message(
    id = id,
    conversationId = conversationId,
    role = MessageRole.USER,
    content = content,
    timestamp = Instant.parse(timestamp)
)
