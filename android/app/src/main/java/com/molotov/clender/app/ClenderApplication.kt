package com.molotov.clender.app

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ProcessLifecycleOwner
import com.molotov.clender.alert.EventAlertRuntimeOwner
import com.molotov.clender.app.ai.AiSubmissionGateway
import com.molotov.clender.app.alert.EventAlertRuntime
import com.molotov.clender.app.widget.WidgetAutomaticRefreshPort
import com.molotov.clender.app.widget.WidgetConfigurationRuntimePort
import com.molotov.clender.domain.conversation.ConversationIdGenerator
import com.molotov.clender.domain.conversation.ConversationManager
import com.molotov.clender.ui.ai.ConversationViewModel
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.widget.ClenderWidgetProvider
import com.molotov.clender.widget.QuickAiActivityOwner
import com.molotov.clender.widget.WidgetAutomaticRefreshOwner
import com.molotov.clender.widget.WidgetConfigurationActivityOwner
import com.molotov.clender.widget.WidgetProviderRuntime
import com.molotov.clender.widget.WidgetProviderRuntimeOwner
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ClenderApplication :
    Application(),
    WidgetProviderRuntimeOwner,
    WidgetConfigurationActivityOwner,
    WidgetAutomaticRefreshOwner,
    QuickAiActivityOwner,
    EventAlertRuntimeOwner {
    val container: AppContainer by lazy {
        AppContainer(this).also { created ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                created.bindWidgetLifecycle(ProcessLifecycleOwner.get().lifecycle)
            } else {
                Handler(Looper.getMainLooper()).post {
                    created.bindWidgetLifecycle(ProcessLifecycleOwner.get().lifecycle)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val provider = ComponentName(this, ClenderWidgetProvider::class.java)
        if (AppWidgetManager.getInstance(this).getAppWidgetIds(provider).any { it > 0 }) {
            container
        }
    }

    override val widgetAutomaticRefreshScope: CoroutineScope
        get() = container.widgetProviderScope

    override val alertRuntime: EventAlertRuntime
        get() = container.alertRuntime

    override val widgetAutomaticRefreshRuntime: WidgetAutomaticRefreshPort
        get() = container.widgetAutomaticRefreshRuntime

    override val widgetProviderScope: CoroutineScope
        get() = container.widgetProviderScope

    override val widgetProviderRuntime: WidgetProviderRuntime
        get() = container.widgetProviderRuntime

    override val widgetConfigurationRuntime: WidgetConfigurationRuntimePort
        get() = container.widgetConfigurationRuntime

    override fun createQuickAiConversationViewModel(defaultTitle: String): ConversationViewModel =
        ConversationViewModel(
            repository = container.conversationRepository,
            manager = ConversationManager(
                repository = container.conversationRepository,
                clock = Clock.systemUTC(),
                idGenerator = ConversationIdGenerator {
                    UUID.randomUUID().toString().replace("-", "")
                },
                defaultTitle = defaultTitle
            ),
            activeStore = container.activeConversationStore
        )

    override val quickAiSubmissionGateway: AiSubmissionGateway
        get() = container.aiSubmissionGateway

    override val quickAiAppearance: Flow<AppearanceUiState>
        get() = widgetConfigurationAppearance

    override val widgetConfigurationAppearance: Flow<AppearanceUiState>
        get() = container.appearance.map { appearance ->
            AppearanceUiState(
                themeMode = when (appearance.theme) {
                    com.molotov.clender.data.settings.ThemeMode.SYSTEM -> ThemeMode.SYSTEM
                    com.molotov.clender.data.settings.ThemeMode.LIGHT -> ThemeMode.LIGHT
                    com.molotov.clender.data.settings.ThemeMode.DARK -> ThemeMode.DARK
                },
                appFontSizeSp = appearance.appFontSizeSp,
                widgetFontSizeSp = appearance.widgetFontSizeSp
            )
        }
}
