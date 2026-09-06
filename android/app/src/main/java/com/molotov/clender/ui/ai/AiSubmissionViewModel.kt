package com.molotov.clender.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molotov.clender.app.ai.AiCoordinatorError
import com.molotov.clender.app.ai.AiCoordinatorState
import com.molotov.clender.app.ai.AiSubmissionDecision
import com.molotov.clender.app.ai.AiSubmissionGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AiSubmissionStatus {
    INACTIVE,
    IDLE,
    WORKING,
    COMPLETED,
    CANCELLED,
    UNCONFIGURED,
    FAILED
}

data class AiSubmissionUiState(
    val isActive: Boolean = false,
    val draft: String = "",
    val status: AiSubmissionStatus = AiSubmissionStatus.INACTIVE,
    val errorCode: AiCoordinatorError? = null
)

class AiSubmissionViewModel(private val runtimeProvider: () -> AiSubmissionGateway) : ViewModel() {
    private val mutableState = MutableStateFlow(AiSubmissionUiState())
    val state: StateFlow<AiSubmissionUiState> = mutableState.asStateFlow()

    private var gateway: AiSubmissionGateway? = null
    private var stateJob: Job? = null
    private var submitJob: Job? = null
    private var submissionInProgress = false
    private var acceptedRequestObserved = false

    fun activate() {
        if (gateway != null) return
        val activatedGateway = runtimeProvider()
        gateway = activatedGateway
        mutableState.value = mutableState.value.copy(
            isActive = true,
            status = AiSubmissionStatus.IDLE,
            errorCode = null
        )
        stateJob = viewModelScope.launch {
            activatedGateway.state.collect(::applyCoordinatorState)
        }
    }

    fun updateDraft(value: String) {
        mutableState.value = mutableState.value.copy(draft = value)
    }

    fun submit(conversationId: String) {
        val activeGateway = gateway ?: return
        val trimmed = mutableState.value.draft.trim()
        if (trimmed.isEmpty() || submissionInProgress ||
            mutableState.value.status == AiSubmissionStatus.WORKING
        ) {
            return
        }
        submissionInProgress = true
        submitJob = viewModelScope.launch {
            try {
                when (activeGateway.submit(conversationId, trimmed)) {
                    AiSubmissionDecision.ACCEPTED -> {
                        acceptedRequestObserved = true
                        mutableState.value = mutableState.value.copy(draft = "")
                    }

                    AiSubmissionDecision.BUSY -> Unit

                    AiSubmissionDecision.UNCONFIGURED -> setPreflightStatus(
                        AiSubmissionStatus.UNCONFIGURED,
                        AiCoordinatorError.CONFIGURATION
                    )

                    AiSubmissionDecision.REJECTED,
                    AiSubmissionDecision.SETTINGS_FAILURE,
                    AiSubmissionDecision.SECRET_FAILURE -> setPreflightStatus(
                        AiSubmissionStatus.FAILED,
                        AiCoordinatorError.CONFIGURATION
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: RuntimeException) {
                setPreflightStatus(AiSubmissionStatus.FAILED, AiCoordinatorError.INTERNAL)
            } finally {
                submissionInProgress = false
            }
        }
    }

    fun dismissStatus() {
        val activeGateway = gateway ?: return
        when (mutableState.value.status) {
            AiSubmissionStatus.COMPLETED,
            AiSubmissionStatus.CANCELLED,
            AiSubmissionStatus.FAILED -> {
                activeGateway.acknowledgeTerminal()
                acceptedRequestObserved = false
                mutableState.value = mutableState.value.copy(
                    status = AiSubmissionStatus.IDLE,
                    errorCode = null
                )
            }

            AiSubmissionStatus.UNCONFIGURED -> mutableState.value = mutableState.value.copy(
                status = AiSubmissionStatus.IDLE,
                errorCode = null
            )

            AiSubmissionStatus.INACTIVE,
            AiSubmissionStatus.IDLE,
            AiSubmissionStatus.WORKING -> Unit
        }
    }

    private fun applyCoordinatorState(coordinatorState: AiCoordinatorState) {
        when (coordinatorState) {
            AiCoordinatorState.Idle -> {
                val completed = acceptedRequestObserved ||
                    mutableState.value.status == AiSubmissionStatus.WORKING
                mutableState.value = mutableState.value.copy(
                    status = if (completed) {
                        AiSubmissionStatus.COMPLETED
                    } else {
                        AiSubmissionStatus.IDLE
                    },
                    errorCode = null
                )
            }

            is AiCoordinatorState.Working -> {
                acceptedRequestObserved = true
                mutableState.value = mutableState.value.copy(
                    status = AiSubmissionStatus.WORKING,
                    errorCode = null
                )
            }

            is AiCoordinatorState.Failed -> mutableState.value = mutableState.value.copy(
                status = AiSubmissionStatus.FAILED,
                errorCode = coordinatorState.error
            )

            AiCoordinatorState.Cancelled -> mutableState.value = mutableState.value.copy(
                status = AiSubmissionStatus.CANCELLED,
                errorCode = null
            )
        }
    }

    private fun setPreflightStatus(status: AiSubmissionStatus, error: AiCoordinatorError) {
        mutableState.value = mutableState.value.copy(status = status, errorCode = error)
    }
}
