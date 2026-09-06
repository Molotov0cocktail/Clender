package com.molotov.clender.ui.state

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.navigation.BackNavigationAction
import com.molotov.clender.ui.navigation.BackNavigationPolicy
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppShellUiState(
    val destination: AppDestination,
    val selectedDate: LocalDate,
    val calendarMode: CalendarMode,
    val drawerOpen: Boolean,
    val childRoutes: List<AppRoute>,
    val draftId: String?
)

class AppDraftController internal constructor(
    private val current: () -> String?,
    private val update: (String?) -> Unit
) {
    fun set(draftId: String) {
        require(DRAFT_ID.matches(draftId)) { "Draft id must be 32 lowercase hex characters" }
        update(draftId)
    }

    fun ensure(): String = current()?.takeIf(DRAFT_ID::matches)
        ?: UUID.randomUUID().toString().replace("-", "").also(::set)

    fun clear() = update(null)

    private companion object {
        val DRAFT_ID = Regex("^[0-9a-f]{32}$")
    }
}

class AppShellViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    private val restored: SavedAppState = AppSavedStateCodec.decode(
        values = mapOf(
            DESTINATION_KEY to (savedStateHandle[DESTINATION_KEY] as? String ?: ""),
            DATE_KEY to (savedStateHandle[DATE_KEY] as? String ?: ""),
            CALENDAR_MODE_KEY to (savedStateHandle[CALENDAR_MODE_KEY] as? String ?: ""),
            DRAFT_ID_KEY to (savedStateHandle[DRAFT_ID_KEY] as? String ?: "")
        ),
        defaultDate = LocalDate.now()
    )

    private val _state = MutableStateFlow(
        AppShellUiState(
            destination = restored.destination,
            selectedDate = restored.selectedDate,
            calendarMode = restored.calendarMode,
            drawerOpen = false,
            childRoutes = emptyList(),
            draftId = restored.draftId
        )
    )

    val state: StateFlow<AppShellUiState> = _state.asStateFlow()
    val drafts = AppDraftController(
        current = { _state.value.draftId },
        update = { draftId ->
            _state.update { it.copy(draftId = draftId) }
            if (draftId == null) savedStateHandle[DRAFT_ID_KEY] = null
            persist()
        }
    )

    fun openDrawer() {
        _state.update { it.copy(drawerOpen = true) }
    }

    fun closeDrawer() {
        _state.update { it.copy(drawerOpen = false) }
    }

    fun navigateTo(destination: AppDestination) {
        _state.update {
            it.copy(
                destination = destination,
                drawerOpen = false,
                childRoutes = emptyList()
            )
        }
        persist()
    }

    fun pushRoute(route: AppRoute) {
        _state.update { it.copy(childRoutes = it.childRoutes + route) }
    }

    fun popRoute(): Boolean {
        if (_state.value.childRoutes.isEmpty()) return false
        _state.update { it.copy(childRoutes = it.childRoutes.dropLast(1)) }
        return true
    }

    fun replaceTopRoute(route: AppRoute) {
        check(_state.value.childRoutes.isNotEmpty()) { "A child route is required" }
        _state.update { it.copy(childRoutes = it.childRoutes.dropLast(1) + route) }
    }

    fun selectDate(date: LocalDate) {
        _state.update { it.copy(selectedDate = date) }
        persist()
    }

    fun changeMode(mode: CalendarMode) {
        _state.update { it.copy(calendarMode = mode) }
        persist()
    }

    fun onBack(drawerOpen: Boolean): BackNavigationAction =
        BackNavigationPolicy.decide(drawerOpen, _state.value.childRoutes.isNotEmpty())

    private fun persist() {
        val current = _state.value
        AppSavedStateCodec.encode(
            SavedAppState(
                destination = current.destination,
                selectedDate = current.selectedDate,
                calendarMode = current.calendarMode,
                draftId = current.draftId
            )
        ).forEach { (key, value) -> savedStateHandle[key] = value }
    }

    private companion object {
        const val DESTINATION_KEY = "destination"
        const val DATE_KEY = "date"
        const val CALENDAR_MODE_KEY = "calendar_mode"
        const val DRAFT_ID_KEY = "draft_id"
    }
}
