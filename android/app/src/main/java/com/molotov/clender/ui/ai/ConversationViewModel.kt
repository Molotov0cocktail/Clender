package com.molotov.clender.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molotov.clender.core.model.Conversation
import com.molotov.clender.core.model.Message
import com.molotov.clender.domain.conversation.ActiveConversationStore
import com.molotov.clender.domain.conversation.ConversationManager
import com.molotov.clender.domain.conversation.ConversationRepository
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

enum class ConversationLoadStatus {
    INACTIVE,
    LOADING,
    READY,
    ERROR
}

enum class ConversationOperation {
    IDLE,
    CREATING,
    RENAMING,
    CLEARING,
    DELETING
}

enum class ConversationErrorCode {
    INITIALIZE_FAILED,
    MESSAGES_FAILED,
    OPERATION_FAILED
}

enum class ConversationPane {
    MESSAGES,
    LIST
}

sealed interface ConversationDialogState {
    data class Rename(val conversationId: String, val input: String) :
        ConversationDialogState

    data class ConfirmClear(val conversationId: String) : ConversationDialogState

    data class ConfirmDelete(val conversationId: String) : ConversationDialogState
}

data class ConversationUiState(
    val loadStatus: ConversationLoadStatus = ConversationLoadStatus.INACTIVE,
    val conversations: List<Conversation> = emptyList(),
    val activeConversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val compactPane: ConversationPane = ConversationPane.MESSAGES,
    val operation: ConversationOperation = ConversationOperation.IDLE,
    val dialog: ConversationDialogState? = null,
    val errorCode: ConversationErrorCode? = null
)

class ConversationViewModel(
    private val repository: ConversationRepository,
    private val manager: ConversationManager,
    private val activeStore: ActiveConversationStore
) : ViewModel() {
    private val _state = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    private val selectionVersion = AtomicLong(0)
    private var activationJob: Job? = null
    private var messageJob: Job? = null
    private var operationJob: Job? = null
    private var activated = false

    val activate: () -> Unit = fun() {
        if (activated) return
        activated = true
        initialize()
    }

    val retry: () -> Unit = fun() {
        val current = _state.value
        if (!activated || current.loadStatus != ConversationLoadStatus.ERROR) return
        when (current.errorCode) {
            ConversationErrorCode.INITIALIZE_FAILED -> initialize()

            ConversationErrorCode.MESSAGES_FAILED -> {
                val active = current.activeConversation ?: return
                _state.value = current.copy(
                    loadStatus = ConversationLoadStatus.LOADING,
                    messages = emptyList(),
                    errorCode = null
                )
                startMessageObservation(active.id)
            }

            ConversationErrorCode.OPERATION_FAILED,
            null -> Unit
        }
    }

    val selectConversation: (String) -> Unit = fun(id: String) {
        val current = _state.value
        if (current.operation == ConversationOperation.IDLE) {
            val selected = current.conversations.firstOrNull { it.id == id }
            if (selected?.id == current.activeConversation?.id) {
                showMessages()
            } else if (selected != null) {
                selectImmediately(selected, current.conversations)
                operationJob = viewModelScope.launch {
                    val version = selectionVersion.get()
                    val result = runCatching { activeStore.setActiveConversationId(id) }
                    result.exceptionOrNull()?.let { failure ->
                        if (failure is CancellationException) throw failure
                        if (version == selectionVersion.get()) setOperationFailure()
                    }
                }
            }
        }
    }

    val createConversation: () -> Unit = fun() {
        val current = _state.value
        if (!canStartOperation(current)) return
        _state.value = current.copy(
            operation = ConversationOperation.CREATING,
            errorCode = null
        )
        operationJob = viewModelScope.launch {
            yield()
            completeOperation(ConversationOperation.CREATING) {
                val created = manager.create()
                val conversations = sortedConversations()
                selectAndPersist(created, conversations)
            }
        }
    }

    val requestRename: (String) -> Unit = fun(conversationId: String) {
        val current = _state.value
        val conversation = current.conversations.firstOrNull { it.id == conversationId } ?: return
        if (!canOpenDialog(current)) return
        _state.value = current.copy(
            dialog = ConversationDialogState.Rename(conversation.id, conversation.title),
            errorCode = null
        )
    }

    val updateRenameTitle: (String) -> Unit = fun(input: String) {
        val current = _state.value
        val dialog = current.dialog as? ConversationDialogState.Rename ?: return
        if (current.operation != ConversationOperation.IDLE) return
        _state.value = current.copy(dialog = dialog.copy(input = input), errorCode = null)
    }

    val confirmRename: () -> Unit = fun() {
        val current = _state.value
        val dialog = current.dialog as? ConversationDialogState.Rename ?: return
        if (!canStartOperation(current) || dialog.input.trim().isEmpty()) return
        _state.value = current.copy(
            operation = ConversationOperation.RENAMING,
            errorCode = null
        )
        operationJob = viewModelScope.launch {
            yield()
            completeOperation(ConversationOperation.RENAMING) {
                val renamed = manager.rename(dialog.conversationId, dialog.input)
                val conversations = sortedConversations()
                val active = if (_state.value.activeConversation?.id == renamed.id) {
                    renamed
                } else {
                    _state.value.activeConversation
                }
                _state.value = _state.value.copy(
                    conversations = conversations,
                    activeConversation = active,
                    operation = ConversationOperation.IDLE,
                    dialog = null,
                    errorCode = null
                )
            }
        }
    }

    val requestClear: (String) -> Unit = fun(conversationId: String) {
        val current = _state.value
        val exists = current.conversations.any { it.id == conversationId }
        if (!exists || !canOpenDialog(current)) return
        _state.value = current.copy(
            dialog = ConversationDialogState.ConfirmClear(conversationId),
            errorCode = null
        )
    }

    val confirmClear: () -> Unit = fun() {
        val current = _state.value
        val dialog = current.dialog as? ConversationDialogState.ConfirmClear ?: return
        if (!canStartOperation(current)) return
        _state.value = current.copy(
            operation = ConversationOperation.CLEARING,
            errorCode = null
        )
        operationJob = viewModelScope.launch {
            yield()
            completeOperation(ConversationOperation.CLEARING) {
                manager.clear(dialog.conversationId)
                val conversations = sortedConversations()
                val active = conversations.firstOrNull {
                    it.id == _state.value.activeConversation?.id
                }
                _state.value = _state.value.copy(
                    conversations = conversations,
                    activeConversation = active,
                    messages = if (active?.id == dialog.conversationId) {
                        emptyList()
                    } else {
                        _state.value.messages
                    },
                    operation = ConversationOperation.IDLE,
                    dialog = null,
                    errorCode = null
                )
            }
        }
    }

    val requestDelete: (String) -> Unit = fun(conversationId: String) {
        val current = _state.value
        val exists = current.conversations.any { it.id == conversationId }
        if (!exists || !canOpenDialog(current)) return
        _state.value = current.copy(
            dialog = ConversationDialogState.ConfirmDelete(conversationId),
            errorCode = null
        )
    }

    val confirmDelete: () -> Unit = fun() {
        val current = _state.value
        val dialog = current.dialog as? ConversationDialogState.ConfirmDelete ?: return
        if (!canStartOperation(current)) return
        _state.value = current.copy(
            operation = ConversationOperation.DELETING,
            errorCode = null
        )
        operationJob = viewModelScope.launch {
            yield()
            completeOperation(ConversationOperation.DELETING) {
                val oldActiveId = _state.value.activeConversation?.id
                val resolved = manager.deleteAndResolveActive(dialog.conversationId, oldActiveId)
                val conversations = sortedConversations()
                if (resolved.id == oldActiveId) {
                    _state.value = _state.value.copy(
                        conversations = conversations,
                        activeConversation = resolved,
                        operation = ConversationOperation.IDLE,
                        dialog = null,
                        errorCode = null
                    )
                } else {
                    selectAndPersist(resolved, conversations)
                }
            }
        }
    }

    val dismissDialog: () -> Unit = fun() {
        val current = _state.value
        if (current.operation != ConversationOperation.IDLE) return
        _state.value = current.copy(dialog = null, errorCode = null)
    }

    val showConversationList: () -> Unit = fun() {
        val current = _state.value
        if (!activated || current.dialog != null) return
        _state.value = current.copy(compactPane = ConversationPane.LIST)
    }

    val showMessages: () -> Unit = fun() {
        val current = _state.value
        if (!activated || current.dialog != null) return
        _state.value = current.copy(compactPane = ConversationPane.MESSAGES)
    }

    override fun onCleared() {
        selectionVersion.incrementAndGet()
        activationJob?.cancel()
        messageJob?.cancel()
        operationJob?.cancel()
        super.onCleared()
    }

    private fun initialize() {
        activationJob?.cancel()
        messageJob?.cancel()
        operationJob?.cancel()
        selectionVersion.incrementAndGet()
        _state.value = ConversationUiState(loadStatus = ConversationLoadStatus.LOADING)
        activationJob = viewModelScope.launch {
            val result = runCatching {
                val preferredId = activeStore.activeConversationId.first()
                val active = manager.resolveActive(preferredId)
                val conversations = sortedConversations()
                activeStore.setActiveConversationId(active.id)
                active to conversations
            }
            val failure = result.exceptionOrNull()
            if (failure is CancellationException) throw failure
            result.fold(
                onSuccess = { (active, conversations) ->
                    _state.value = ConversationUiState(
                        loadStatus = ConversationLoadStatus.READY,
                        conversations = conversations,
                        activeConversation = active
                    )
                    startMessageObservation(active.id)
                },
                onFailure = {
                    _state.value = ConversationUiState(
                        loadStatus = ConversationLoadStatus.ERROR,
                        errorCode = ConversationErrorCode.INITIALIZE_FAILED
                    )
                }
            )
        }
    }

    private fun selectImmediately(selected: Conversation, conversations: List<Conversation>) {
        selectionVersion.incrementAndGet()
        messageJob?.cancel()
        _state.value = _state.value.copy(
            loadStatus = ConversationLoadStatus.READY,
            conversations = conversations,
            activeConversation = selected,
            messages = emptyList(),
            compactPane = ConversationPane.MESSAGES,
            operation = ConversationOperation.IDLE,
            dialog = null,
            errorCode = null
        )
        startMessageObservation(selected.id)
    }

    private suspend fun selectAndPersist(
        selected: Conversation,
        conversations: List<Conversation>
    ) {
        activeStore.setActiveConversationId(selected.id)
        selectImmediately(selected, conversations)
    }

    private fun startMessageObservation(conversationId: String) {
        val version = selectionVersion.incrementAndGet()
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            try {
                repository.observeMessages(conversationId).collect { messages ->
                    val refreshedConversation = repository.findConversation(conversationId)
                    if (
                        version == selectionVersion.get() &&
                        _state.value.activeConversation?.id == conversationId &&
                        refreshedConversation != null
                    ) {
                        val refreshedList = _state.value.conversations.map { conversation ->
                            if (conversation.id == conversationId) {
                                refreshedConversation
                            } else {
                                conversation
                            }
                        }
                        _state.value = _state.value.copy(
                            loadStatus = ConversationLoadStatus.READY,
                            conversations = refreshedList,
                            activeConversation = refreshedConversation,
                            messages = messages.sortedWith(
                                compareBy(Message::timestamp, Message::id)
                            ),
                            errorCode = null
                        )
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                if (
                    version == selectionVersion.get() &&
                    _state.value.activeConversation?.id == conversationId
                ) {
                    _state.value = _state.value.copy(
                        loadStatus = ConversationLoadStatus.ERROR,
                        messages = emptyList(),
                        errorCode = ConversationErrorCode.MESSAGES_FAILED
                    )
                }
            }
        }
    }

    private suspend fun sortedConversations(): List<Conversation> =
        repository.listConversations().sortedWith(
            compareBy(Conversation::createdAt, Conversation::id)
        )

    private fun canStartOperation(state: ConversationUiState): Boolean = activated &&
        state.loadStatus == ConversationLoadStatus.READY &&
        state.operation == ConversationOperation.IDLE &&
        operationJob?.isActive != true

    private fun canOpenDialog(state: ConversationUiState): Boolean =
        canStartOperation(state) && state.dialog == null

    private suspend fun completeOperation(
        expectedOperation: ConversationOperation,
        block: suspend () -> Unit
    ) {
        try {
            block()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            if (_state.value.operation == expectedOperation) setOperationFailure()
        }
    }

    private fun setOperationFailure() {
        _state.value = _state.value.copy(
            operation = ConversationOperation.IDLE,
            errorCode = ConversationErrorCode.OPERATION_FAILED
        )
    }
}
