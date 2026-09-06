package com.molotov.clender.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.molotov.clender.app.ai.AiCoordinatorState
import com.molotov.clender.app.ai.AiSubmissionDecision
import com.molotov.clender.app.ai.AiSubmissionGateway
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import com.molotov.clender.domain.conversation.ActiveConversationStore
import com.molotov.clender.domain.conversation.ConversationIdGenerator
import com.molotov.clender.domain.conversation.ConversationManager
import com.molotov.clender.domain.conversation.ConversationRepository
import com.molotov.clender.ui.ai.ConversationViewModel
import com.molotov.clender.ui.foundation.AppearanceUiState
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.robolectric.Shadows

internal const val QUICK_AI_TEST_CONVERSATION = "11111111111111111111111111111111"
internal const val QUICK_AI_TEST_DRAFT = "  安排 🌏\n明天 09:00  "

class QuickAiTestApplication :
    Application(),
    QuickAiActivityOwner {
    internal val repository = QuickAiTestRepository()
    internal val active = MutableStateFlow<String?>(QUICK_AI_TEST_CONVERSATION)
    internal val gateway = QuickAiTestGateway()
    internal val appearance = MutableStateFlow(AppearanceUiState.fromPersisted(null, null, null))
    internal var runtimeReads = 0
    internal var modelCreations = 0
    internal var activeWrites = 0

    override fun createQuickAiConversationViewModel(defaultTitle: String): ConversationViewModel {
        runtimeReads += 1
        modelCreations += 1
        return ConversationViewModel(
            repository,
            ConversationManager(
                repository,
                Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC),
                ConversationIdGenerator { "22222222222222222222222222222222" },
                defaultTitle
            ),
            object : ActiveConversationStore {
                override val activeConversationId: Flow<String?> = active

                override suspend fun setActiveConversationId(id: String?) {
                    activeWrites += 1
                    active.value = id
                }
            }
        )
    }

    override val quickAiSubmissionGateway: AiSubmissionGateway
        get() {
            runtimeReads += 1
            return gateway
        }

    override val quickAiAppearance: Flow<AppearanceUiState>
        get() {
            runtimeReads += 1
            return appearance
        }
}

internal class QuickAiTestGateway : AiSubmissionGateway {
    override val state = MutableStateFlow<AiCoordinatorState>(AiCoordinatorState.Idle)
    var decision = AiSubmissionDecision.ACCEPTED
    val submissions = mutableListOf<Pair<String, String>>()

    override suspend fun submit(conversationId: String, message: String): AiSubmissionDecision {
        submissions += conversationId to message
        if (decision == AiSubmissionDecision.ACCEPTED) {
            state.value = AiCoordinatorState.Working(conversationId)
        }
        return decision
    }

    override fun acknowledgeTerminal() {
        state.value = AiCoordinatorState.Idle
    }
}

internal class QuickAiTestRepository : ConversationRepository {
    val conversations = linkedMapOf(
        QUICK_AI_TEST_CONVERSATION to Conversation(
            QUICK_AI_TEST_CONVERSATION,
            "private conversation title",
            Instant.parse("2026-09-06T00:00:00Z"),
            0
        )
    )
    val messages = MutableStateFlow<List<Message>>(emptyList())
    var readGate: CompletableDeferred<Unit>? = null
    var failReads = false
    var createCalls = 0
    var collectors = 0

    override suspend fun create(conversation: Conversation): Conversation {
        createCalls += 1
        conversations[conversation.id] = conversation
        return conversation
    }

    override suspend fun findConversation(id: String): Conversation? {
        awaitRead()
        return conversations[id]
    }

    override suspend fun listConversations(): List<Conversation> {
        awaitRead()
        return conversations.values.toList()
    }

    override fun observeMessages(conversationId: String): Flow<List<Message>> = flow {
        collectors += 1
        try {
            emitAll(messages)
        } finally {
            collectors -= 1
        }
    }

    override suspend fun appendMessageAndIncrementTokens(
        conversationId: String,
        role: MessageRole,
        content: String,
        timestamp: Instant,
        tokenDelta: Int
    ): Message = error("Quick AI Activity must submit through the existing gateway")

    override suspend fun rename(id: String, title: String): Conversation? =
        error("Quick AI Activity must not rename conversations")

    override suspend fun clearMessagesAndResetTokens(id: String): Boolean =
        error("Quick AI Activity must not clear conversations")

    override suspend fun delete(id: String): Boolean =
        error("Quick AI Activity must not delete conversations")

    private suspend fun awaitRead() {
        readGate?.await()
        check(!failReads) { "private repository failure https://example.invalid/body" }
    }
}

internal fun bindQuickAiTestWidget(context: Context, id: Int = 71) {
    Shadows.shadowOf(AppWidgetManager.getInstance(context)).putWidgetInfo(
        id,
        AppWidgetProviderInfo().apply {
            provider = ComponentName(context, ClenderWidgetProvider::class.java)
        }
    )
}

internal fun quickAiTestIntent(context: Context, id: Int = 71): Intent =
    Intent("com.molotov.clender.action.WIDGET_QUICK_AI").apply {
        component = ComponentName(context, QuickAiActivity::class.java)
        setPackage(context.packageName)
        data = Uri.parse("clender-internal://widget/$id/quick-ai")
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
    }
