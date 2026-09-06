package com.molotov.clender.ui.event

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molotov.clender.core.model.Event
import com.molotov.clender.domain.event.EventNotFoundException
import com.molotov.clender.domain.event.EventRepository
import com.molotov.clender.domain.event.EventService
import com.molotov.clender.domain.event.EventValidationException
import com.molotov.clender.domain.event.EventValidator
import com.molotov.clender.domain.event.InvalidEventPatchException
import com.molotov.clender.domain.event.TombstonedEventException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

sealed interface EventCrudRoute {
    data class Detail(val id: Long) : EventCrudRoute

    data class New(val date: LocalDate) : EventCrudRoute

    data class Edit(val id: Long) : EventCrudRoute
}

enum class EventCrudLoadStatus {
    IDLE,
    LOADING,
    CONTENT,
    NOT_FOUND,
    ERROR
}

enum class EventCrudOperation {
    IDLE,
    SAVING,
    DELETING
}

enum class EventDetailErrorCode {
    LOAD_FAILED
}

enum class EventSaveErrorCode {
    VALIDATION_FAILED,
    NOT_FOUND,
    SAVE_FAILED
}

enum class EventDeleteErrorCode {
    NOT_FOUND,
    DELETE_FAILED
}

data class EventCrudUiState(
    val route: EventCrudRoute? = null,
    val loadStatus: EventCrudLoadStatus = EventCrudLoadStatus.IDLE,
    val event: Event? = null,
    val form: EventFormState? = null,
    val operation: EventCrudOperation = EventCrudOperation.IDLE,
    val detailError: EventDetailErrorCode? = null,
    val saveError: EventSaveErrorCode? = null,
    val deleteError: EventDeleteErrorCode? = null
)

sealed interface EventCrudEffect {
    data class ReplaceWithDetail(val id: Long) : EventCrudEffect

    data class ShowDetail(val id: Long) : EventCrudEffect

    data object PopRoute : EventCrudEffect
}

class EventCrudViewModel(
    private val repository: EventRepository,
    private val eventService: EventService
) : ViewModel() {
    private val _state = MutableStateFlow(EventCrudUiState())
    val state: StateFlow<EventCrudUiState> = _state.asStateFlow()

    private val effectChannel = Channel<EventCrudEffect>(capacity = Channel.BUFFERED)
    val effects: Flow<EventCrudEffect> = effectChannel.receiveAsFlow()

    private var routeVersion: Long = 0
    private var loadJob: Job? = null
    private var operationJob: Job? = null

    fun openDetail(id: Long) {
        beginRoute(EventCrudRoute.Detail(id))
        if (id <= 0L) {
            _state.value = EventCrudUiState(
                route = EventCrudRoute.Detail(id),
                loadStatus = EventCrudLoadStatus.NOT_FOUND
            )
            return
        }
        loadDetail(id)
    }

    fun startNew(date: LocalDate) {
        beginRoute(EventCrudRoute.New(date))
        _state.value = EventCrudUiState(
            route = EventCrudRoute.New(date),
            loadStatus = EventCrudLoadStatus.CONTENT,
            form = EventFormState.new(date)
        )
    }

    fun startEdit() {
        val current = _state.value
        val event = current.event?.takeIf { current.loadStatus == EventCrudLoadStatus.CONTENT }
            ?: return
        if (event.id <= 0L || event.deletedAt != null) return
        beginRoute(EventCrudRoute.Edit(event.id))
        _state.value = EventCrudUiState(
            route = EventCrudRoute.Edit(event.id),
            loadStatus = EventCrudLoadStatus.CONTENT,
            event = event,
            form = EventFormState.fromEvent(event)
        )
    }

    fun updateForm(form: EventFormState) {
        val current = _state.value
        if (current.operation != EventCrudOperation.IDLE) return
        if (current.route !is EventCrudRoute.New && current.route !is EventCrudRoute.Edit) return
        _state.value = current.copy(form = form, saveError = null)
    }

    fun retry() {
        val current = _state.value
        val route = (current.route as? EventCrudRoute.Detail)
            ?.takeIf { current.operation == EventCrudOperation.IDLE }
            ?: return
        routeVersion += 1
        loadJob?.cancel()
        loadDetail(route.id)
    }

    fun save() {
        val current = _state.value
        val form = current.form.takeIf { current.operation == EventCrudOperation.IDLE } ?: return
        val route = current.route
        if (route !is EventCrudRoute.New && route !is EventCrudRoute.Edit) return
        if (!form.canSubmit) {
            _state.value = current.copy(saveError = EventSaveErrorCode.VALIDATION_FAILED)
        } else {
            val version = routeVersion
            _state.value = current.copy(
                operation = EventCrudOperation.SAVING,
                saveError = null,
                deleteError = null
            )
            operationJob = viewModelScope.launch {
                yield()
                val result = runCatching {
                    performSave(route, form, current.event, eventService)
                }
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                if (version == routeVersion) {
                    result.fold(
                        onSuccess = { saved ->
                            _state.value = EventCrudUiState(
                                route = EventCrudRoute.Detail(saved.event.id),
                                loadStatus = EventCrudLoadStatus.CONTENT,
                                event = saved.event,
                                operation = EventCrudOperation.IDLE
                            )
                            effectChannel.trySend(saved.effect)
                        },
                        onFailure = { failure ->
                            _state.value = _state.value.copy(
                                operation = EventCrudOperation.IDLE,
                                saveError = failure.toSaveErrorCode()
                            )
                        }
                    )
                }
            }
        }
    }

    fun delete() {
        val current = _state.value
        val route = (current.route as? EventCrudRoute.Detail)
            ?.takeIf {
                current.operation == EventCrudOperation.IDLE &&
                    current.loadStatus == EventCrudLoadStatus.CONTENT &&
                    current.event?.id == it.id &&
                    it.id > 0L
            }
            ?: return
        val version = routeVersion
        _state.value = current.copy(
            operation = EventCrudOperation.DELETING,
            deleteError = null,
            saveError = null
        )
        operationJob = viewModelScope.launch {
            yield()
            val result = runCatching { eventService.delete(route.id) }
            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
            if (version == routeVersion) {
                result.fold(
                    onSuccess = { deleted ->
                        if (deleted) {
                            _state.value = _state.value.copy(operation = EventCrudOperation.IDLE)
                            effectChannel.trySend(EventCrudEffect.PopRoute)
                        } else {
                            _state.value = _state.value.copy(
                                operation = EventCrudOperation.IDLE,
                                deleteError = EventDeleteErrorCode.NOT_FOUND
                            )
                        }
                    },
                    onFailure = { failure ->
                        _state.value = _state.value.copy(
                            operation = EventCrudOperation.IDLE,
                            deleteError = failure.toDeleteErrorCode()
                        )
                    }
                )
            }
        }
    }

    fun clearRoute() {
        routeVersion += 1
        loadJob?.cancel()
        operationJob?.cancel()
        loadJob = null
        operationJob = null
        _state.value = EventCrudUiState()
    }

    private fun beginRoute(route: EventCrudRoute) {
        routeVersion += 1
        loadJob?.cancel()
        operationJob?.cancel()
        loadJob = null
        operationJob = null
        _state.value = EventCrudUiState(route = route)
    }

    private fun loadDetail(id: Long) {
        val version = routeVersion
        _state.value = EventCrudUiState(
            route = EventCrudRoute.Detail(id),
            loadStatus = EventCrudLoadStatus.LOADING
        )
        loadJob = viewModelScope.launch {
            yield()
            try {
                val found = repository.findById(id)
                val event = found
                    ?.takeIf { it.deletedAt == null }
                    ?.let(EventValidator::validatePersisted)
                if (version != routeVersion) return@launch
                _state.value = if (event == null || event.deletedAt != null) {
                    EventCrudUiState(
                        route = EventCrudRoute.Detail(id),
                        loadStatus = EventCrudLoadStatus.NOT_FOUND
                    )
                } else {
                    EventCrudUiState(
                        route = EventCrudRoute.Detail(id),
                        loadStatus = EventCrudLoadStatus.CONTENT,
                        event = event
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (version == routeVersion) {
                    _state.value = EventCrudUiState(
                        route = EventCrudRoute.Detail(id),
                        loadStatus = EventCrudLoadStatus.ERROR,
                        detailError = EventDetailErrorCode.LOAD_FAILED
                    )
                }
            }
        }
    }
}

private data class SaveResult(val event: Event, val effect: EventCrudEffect)

private suspend fun performSave(
    route: EventCrudRoute,
    form: EventFormState,
    original: Event?,
    eventService: EventService
): SaveResult = when (route) {
    is EventCrudRoute.New -> {
        val stored = eventService.add(form.toAddCommand())
        SaveResult(stored, EventCrudEffect.ReplaceWithDetail(stored.id))
    }

    is EventCrudRoute.Edit -> {
        val current = original ?: throw EventNotFoundException(route.id)
        val patch = form.toPatch(current)
        val stored = if (patch.isEmpty()) current else eventService.update(route.id, patch)
        SaveResult(stored, EventCrudEffect.ShowDetail(route.id))
    }

    is EventCrudRoute.Detail -> error("Detail route cannot be saved")
}

private fun Throwable.toSaveErrorCode(): EventSaveErrorCode = when (this) {
    is EventNotFoundException,
    is TombstonedEventException -> EventSaveErrorCode.NOT_FOUND

    is EventValidationException,
    is InvalidEventPatchException -> EventSaveErrorCode.VALIDATION_FAILED

    else -> EventSaveErrorCode.SAVE_FAILED
}

private fun Throwable.toDeleteErrorCode(): EventDeleteErrorCode = when (this) {
    is EventNotFoundException,
    is TombstonedEventException -> EventDeleteErrorCode.NOT_FOUND

    else -> EventDeleteErrorCode.DELETE_FAILED
}
