package com.molotov.clender.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molotov.clender.R
import com.molotov.clender.domain.conversation.ConversationIdGenerator
import com.molotov.clender.domain.conversation.ConversationManager
import com.molotov.clender.ui.ai.AiSubmissionViewModel
import com.molotov.clender.ui.ai.ConversationViewModel
import com.molotov.clender.ui.app.ClenderApp
import com.molotov.clender.ui.app.ClenderAppAiModels
import com.molotov.clender.ui.app.ClenderAppModels
import com.molotov.clender.ui.app.ClenderAppSettingsModels
import com.molotov.clender.ui.calendar.CalendarViewModel
import com.molotov.clender.ui.event.EventCrudViewModel
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.settings.SettingsViewModel
import com.molotov.clender.ui.state.AppShellViewModel
import com.molotov.clender.ui.theme.AppearanceViewModel
import com.molotov.clender.widget.WidgetActionIntentContract
import com.molotov.clender.widget.WidgetEditEntryRouter
import com.molotov.clender.widget.WidgetQuickAiEntryRouter
import java.time.Clock
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shellViewModel = ViewModelProvider(this)[AppShellViewModel::class.java]
        if (savedInstanceState == null) routeWidgetEntry(intent)
        val initialShellState = shellViewModel.state.value
        val application = application as ClenderApplication
        val container = application.container
        val calendarViewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = requireNotNull(
                    modelClass.cast(
                        CalendarViewModel(
                            repository = container.eventRepository,
                            initialSelectedDate = initialShellState.selectedDate,
                            initialMode = initialShellState.calendarMode,
                            locale = resources.configuration.locales[0]
                        )
                    )
                )
            }
        )[CalendarViewModel::class.java]
        val eventCrudViewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = requireNotNull(
                    modelClass.cast(
                        EventCrudViewModel(
                            repository = container.eventRepository,
                            eventService = container.eventService
                        )
                    )
                )
            }
        )[EventCrudViewModel::class.java]
        val conversationViewModel = conversationViewModel(container)
        val appearanceViewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = requireNotNull(
                    modelClass.cast(AppearanceViewModel(container.appearance))
                )
            }
        )[AppearanceViewModel::class.java]
        setContent {
            val shellState by shellViewModel.state.collectAsStateWithLifecycle()
            val needsAiModels = shellState.destination == AppDestination.AI ||
                shellState.childRoutes.lastOrNull() == AppRoute.QuickAi
            val needsSettingsModel = needsAiModels ||
                shellState.destination == AppDestination.SETTINGS
            val aiSubmissionViewModel = if (needsAiModels) {
                aiSubmissionViewModel(container)
            } else {
                null
            }
            val settingsViewModel = if (needsSettingsModel) {
                settingsViewModel(container)
            } else {
                null
            }
            ClenderApp(
                viewModel = shellViewModel,
                calendarViewModel = calendarViewModel,
                eventCrudViewModel = eventCrudViewModel,
                models = ClenderAppModels(
                    ai = ClenderAppAiModels(
                        conversation = conversationViewModel,
                        submission = aiSubmissionViewModel
                    ),
                    settings = ClenderAppSettingsModels(
                        appearance = appearanceViewModel,
                        settings = settingsViewModel
                    )
                )
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (routeWidgetEntry(intent)) setIntent(intent)
    }

    private fun routeWidgetEntry(intent: Intent): Boolean {
        val editEntry = WidgetActionIntentContract.validateEditEvent(this, intent)
        return if (editEntry != null) {
            WidgetEditEntryRouter.route(
                ViewModelProvider(this)[AppShellViewModel::class.java],
                editEntry
            )
            true
        } else {
            WidgetActionIntentContract.validateQuickAiNavigation(this, intent)
                ?.let { quickAiEntry ->
                    WidgetQuickAiEntryRouter.route(
                        ViewModelProvider(this)[AppShellViewModel::class.java],
                        quickAiEntry
                    ) {
                        settingsViewModel((application as ClenderApplication).container)
                            .showAiSection()
                    }
                    true
                } ?: false
        }
    }

    private fun conversationViewModel(container: AppContainer): ConversationViewModel =
        ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = requireNotNull(
                    modelClass.cast(
                        ConversationViewModel(
                            repository = container.conversationRepository,
                            manager = ConversationManager(
                                repository = container.conversationRepository,
                                clock = Clock.systemUTC(),
                                idGenerator = ConversationIdGenerator {
                                    UUID.randomUUID().toString().replace("-", "")
                                },
                                defaultTitle = getString(R.string.conversation_default_title)
                            ),
                            activeStore = container.activeConversationStore
                        )
                    )
                )
            }
        )[ConversationViewModel::class.java]

    private fun aiSubmissionViewModel(container: AppContainer): AiSubmissionViewModel =
        ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = requireNotNull(
                    modelClass.cast(AiSubmissionViewModel { container.aiSubmissionGateway })
                )
            }
        )[AiSubmissionViewModel::class.java]

    private fun settingsViewModel(container: AppContainer): SettingsViewModel = ViewModelProvider(
        this,
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = requireNotNull(
                modelClass.cast(SettingsViewModel(container.settingsPort))
            )
        }
    )[SettingsViewModel::class.java]
}
