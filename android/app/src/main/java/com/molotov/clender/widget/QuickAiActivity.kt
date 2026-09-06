package com.molotov.clender.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molotov.clender.R
import com.molotov.clender.app.ai.AiSubmissionGateway
import com.molotov.clender.domain.widget.WidgetActionSpec
import com.molotov.clender.ui.ai.AiSubmissionViewModel
import com.molotov.clender.ui.ai.ConversationLoadStatus
import com.molotov.clender.ui.ai.ConversationViewModel
import com.molotov.clender.ui.ai.QuickAiActions
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.theme.ClenderTheme
import kotlinx.coroutines.flow.Flow

interface QuickAiActivityOwner {
    fun createQuickAiConversationViewModel(defaultTitle: String): ConversationViewModel

    val quickAiSubmissionGateway: AiSubmissionGateway
    val quickAiAppearance: Flow<AppearanceUiState>
}

class QuickAiActivity : ComponentActivity() {
    private var widgetEntry: WidgetActionSpec.QuickAi? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entry = WidgetActionIntentContract.validateQuickAi(this, intent)
        if (entry == null) {
            finish()
            return
        }
        widgetEntry = entry
        showQuickAi(application as QuickAiActivityOwner)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val entry = WidgetActionIntentContract.validateQuickAi(this, intent) ?: return
        widgetEntry = entry
        setIntent(intent)
    }

    private fun showQuickAi(owner: QuickAiActivityOwner) {
        val conversations = activityModel(ConversationViewModel::class.java) {
            owner.createQuickAiConversationViewModel(getString(R.string.conversation_default_title))
        }
        val submission = activityModel(AiSubmissionViewModel::class.java) {
            AiSubmissionViewModel { owner.quickAiSubmissionGateway }
        }
        val appearanceFlow = owner.quickAiAppearance
        setContent {
            val appearance by appearanceFlow.collectAsStateWithLifecycle(
                initialValue = AppearanceUiState.fromPersisted(null, null, null)
            )
            val conversationState by conversations.state.collectAsStateWithLifecycle()
            val submissionState by submission.state.collectAsStateWithLifecycle()
            LaunchedEffect(conversations, submission) {
                conversations.activate()
                submission.activate()
            }
            BackHandler(onBack = ::finish)
            ClenderTheme(appearance) {
                QuickAiActivityScreen(
                    conversationState = conversationState,
                    submissionState = submissionState,
                    onRetry = conversations.retry,
                    actions = QuickAiActions(
                        onDraftChange = submission::updateDraft,
                        onSend = { submitReadyConversation(conversations, submission) },
                        onDismissStatus = submission::dismissStatus,
                        onOpenConversation = { openMain(WidgetQuickAiDestination.CONVERSATION) },
                        onOpenSettings = { openMain(WidgetQuickAiDestination.SETTINGS) },
                        onBack = ::finish
                    )
                )
            }
        }
    }

    private fun openMain(destination: WidgetQuickAiDestination) {
        val entry = widgetEntry ?: return
        startActivity(
            WidgetActionIntentContract.quickAiNavigationIntent(this, entry.widgetId, destination)
        )
    }

    private fun <T : ViewModel> activityModel(type: Class<T>, create: () -> T): T =
        ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                override fun <V : ViewModel> create(modelClass: Class<V>): V =
                    requireNotNull(modelClass.cast(create()))
            }
        )[type]
}

private fun submitReadyConversation(
    conversations: ConversationViewModel,
    submission: AiSubmissionViewModel
) {
    val state = conversations.state.value
    val id = state.activeConversation?.id
    if (state.loadStatus == ConversationLoadStatus.READY && !id.isNullOrBlank()) {
        submission.submit(id)
    }
}
