package com.molotov.clender.ui.ai

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import com.molotov.clender.domain.conversation.ActiveConversationStore
import com.molotov.clender.domain.conversation.ConversationIdGenerator
import com.molotov.clender.domain.conversation.ConversationManager
import com.molotov.clender.domain.conversation.ConversationRepository
import com.molotov.clender.domain.event.EventRepository
import com.molotov.clender.domain.event.EventService
import com.molotov.clender.domain.event.ScheduleMutationSink
import com.molotov.clender.domain.event.SyncUidGenerator
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.app.ClenderApp
import com.molotov.clender.ui.app.ClenderAppAiModels
import com.molotov.clender.ui.app.ClenderAppModels
import com.molotov.clender.ui.calendar.CalendarViewModel
import com.molotov.clender.ui.event.EventCrudViewModel
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.state.AppShellViewModel
import com.molotov.clender.ui.state.CalendarMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

internal class ConversationEntryTestHost : AutoCloseable {
    val host = RobolectricComposeHost()
    val repository = EntryConversationRepository()
    lateinit var shell: AppShellViewModel
    lateinit var conversations: ConversationViewModel
    private lateinit var calendar: CalendarViewModel
    private lateinit var editor: EventCrudViewModel

    fun start() {
        host.start()
        val events = EntryEventRepository()
        shell = AppShellViewModel(SavedStateHandle())
        calendar = CalendarViewModel(
            events,
            LocalDate.of(2026, 9, 6),
            CalendarMode.MONTH,
            Locale.US
        )
        editor = EventCrudViewModel(
            events,
            EventService(
                events,
                Clock.systemUTC(),
                SyncUidGenerator { "1".repeat(32) },
                ScheduleMutationSink { error("No event writes") }
            )
        )
        conversations = ConversationViewModel(
            repository,
            ConversationManager(
                repository,
                Clock.systemUTC(),
                ConversationIdGenerator { "b".repeat(32) },
                "New"
            ),
            object : ActiveConversationStore {
                override val activeConversationId = MutableStateFlow<String?>(null)
                override suspend fun setActiveConversationId(id: String?) {
                    activeConversationId.value = id
                }
            }
        )
        host.activity.viewModelStore.put("shell", shell)
        host.activity.viewModelStore.put("calendar", calendar)
        host.activity.viewModelStore.put("editor", editor)
        host.activity.viewModelStore.put("conversations", conversations)
        shell.navigateTo(AppDestination.AI)
    }

    fun render(appearance: AppearanceUiState, fontScale: Float) {
        host.activity.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    host.activity.resources.displayMetrics.density,
                    fontScale
                )
            ) {
                ClenderApp(
                    shell,
                    calendar,
                    editor,
                    appearance,
                    ClenderAppModels(ai = ClenderAppAiModels(conversation = conversations))
                )
            }
        }
    }

    override fun close() = host.close()
}

internal class EntryConversationRepository : ConversationRepository {
    private val timestamp = Instant.parse("2026-09-06T00:00:00Z")
    private var conversation = Conversation("a".repeat(32), "Synthetic conversation", timestamp, 0)
    private val created = mutableListOf<Conversation>()
    var clearCalls = 0
    var failLoad = false
    var empty = false
    var loadGate: CompletableDeferred<Unit>? = null

    override suspend fun listConversations(): List<Conversation> {
        loadGate?.await()
        check(!failLoad) { "Synthetic failure" }
        return if (empty) emptyList() else listOf(conversation) + created
    }

    override suspend fun findConversation(id: String): Conversation? =
        (listOf(conversation) + created).firstOrNull { !empty && it.id == id }

    override suspend fun create(conversation: Conversation): Conversation {
        if (empty) this.conversation = conversation else created.add(conversation)
        empty = false
        return conversation
    }

    override fun observeMessages(conversationId: String): Flow<List<Message>> = flowOf(
        listOf(
            Message(1, conversationId, MessageRole.ASSISTANT, "First synthetic reply", timestamp)
        )
    )

    override suspend fun appendMessageAndIncrementTokens(
        conversationId: String,
        role: MessageRole,
        content: String,
        timestamp: Instant,
        tokenDelta: Int
    ): Message = error("No network or message writes")

    override suspend fun rename(id: String, title: String): Conversation =
        error("Unexpected rename")
    override suspend fun clearMessagesAndResetTokens(id: String): Boolean =
        true.also { clearCalls++ }
    override suspend fun delete(id: String): Boolean = error("Unexpected delete")
}

private class EntryEventRepository : EventRepository {
    override suspend fun findById(id: Long): Event? = null
    override suspend fun insert(event: Event): Event = error("No event writes")
    override suspend fun update(event: Event): Event = error("No event writes")
    override fun observeRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> =
        flowOf(emptyList())
}
