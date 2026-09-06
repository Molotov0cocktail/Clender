package com.molotov.clender.ui.app

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.core.model.Event
import com.molotov.clender.domain.event.EventRepository
import com.molotov.clender.domain.event.EventService
import com.molotov.clender.domain.event.ScheduleMutationSink
import com.molotov.clender.domain.event.SyncUidGenerator
import com.molotov.clender.ui.calendar.CalendarViewModel
import com.molotov.clender.ui.event.EventCrudViewModel
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.settings.AiSettingsDraft
import com.molotov.clender.ui.settings.ApiKeyMutation
import com.molotov.clender.ui.settings.AppearanceSettingsDraft
import com.molotov.clender.ui.settings.ModelFetchResult
import com.molotov.clender.ui.settings.PersistedSettings
import com.molotov.clender.ui.settings.SettingsPort
import com.molotov.clender.ui.settings.SettingsSaveDecision
import com.molotov.clender.ui.settings.SettingsViewModel
import com.molotov.clender.ui.state.AppShellViewModel
import com.molotov.clender.ui.state.CalendarMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

internal class DrawerStateTestHost : AutoCloseable {
    private lateinit var controller: ActivityController<ComponentActivity>
    val appearance = mutableStateOf(AppearanceUiState(ThemeMode.LIGHT, 13, 13))
    val savedState = SavedStateHandle()
    val settingsPort = DrawerSettingsPort()
    private val repository = DrawerReadOnlyRepository()
    private val factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
            when (modelClass) {
                AppShellViewModel::class.java -> AppShellViewModel(savedState)

                CalendarViewModel::class.java -> CalendarViewModel(
                    repository,
                    LocalDate.of(2026, 9, 6),
                    CalendarMode.MONTH,
                    Locale.US
                )

                EventCrudViewModel::class.java -> EventCrudViewModel(
                    repository,
                    EventService(
                        repository,
                        Clock.systemUTC(),
                        SyncUidGenerator { "1".repeat(32) },
                        ScheduleMutationSink { error("Drawer must not write events") }
                    )
                )

                SettingsViewModel::class.java -> SettingsViewModel(settingsPort)

                else -> error("Unexpected test ViewModel")
            }
        )
    }

    val activity: ComponentActivity
        get() = controller.get()

    val shell: AppShellViewModel
        get() = models()[AppShellViewModel::class.java]

    val settings: SettingsViewModel
        get() = models()[SettingsViewModel::class.java]

    val editor: EventCrudViewModel
        get() = models()[EventCrudViewModel::class.java]

    fun start() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    }

    fun render() {
        val shell = shell
        val calendar = models()[CalendarViewModel::class.java]
        val editor = editor
        val settings = settings
        activity.setContent {
            ClenderApp(
                shell,
                calendar,
                editor,
                appearance = appearance.value,
                models = ClenderAppModels(
                    settings = ClenderAppSettingsModels(settings = settings)
                )
            )
        }
    }

    fun recreate() {
        controller.recreate()
        render()
    }

    private fun models() = ViewModelProvider(activity, factory)

    override fun close() {
        try {
            if (::controller.isInitialized) controller.pause().stop().destroy()
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}

internal class DrawerSettingsPort : SettingsPort {
    var failLoad = false

    override suspend fun load(): PersistedSettings {
        check(!failLoad) { "Synthetic unavailable settings" }
        return PersistedSettings()
    }

    override suspend fun saveAppearance(draft: AppearanceSettingsDraft): SettingsSaveDecision =
        error("Drawer must not save settings")

    override suspend fun saveAi(
        draft: AiSettingsDraft,
        mutation: ApiKeyMutation,
        key: CharArray?
    ): SettingsSaveDecision = error("Drawer must not save AI settings")

    override suspend fun fetchModels(
        draft: AiSettingsDraft,
        mutation: ApiKeyMutation,
        key: CharArray?
    ): ModelFetchResult = error("Drawer must not fetch models")

    override fun cancelModelFetch() = Unit
}

private class DrawerReadOnlyRepository : EventRepository {
    override suspend fun findById(id: Long): Event? = null
    override suspend fun insert(event: Event): Event = error("Drawer must not insert")
    override suspend fun update(event: Event): Event = error("Drawer must not update")
    override fun observeRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> =
        flowOf(emptyList())
}
