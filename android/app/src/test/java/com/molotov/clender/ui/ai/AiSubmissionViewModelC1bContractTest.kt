package com.molotov.clender.ui.ai

import android.os.Looper
import androidx.lifecycle.ViewModel
import com.molotov.clender.app.ai.AiCoordinatorError
import com.molotov.clender.app.ai.AiCoordinatorState
import com.molotov.clender.app.ai.AiSubmissionDecision
import com.molotov.clender.app.ai.AiSubmissionGateway
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AiSubmissionViewModelC1bContractTest {
    private val trackedViewModels = mutableListOf<AiSubmissionViewModel>()

    @After
    fun clearViewModels() {
        trackedViewModels.forEach(::clearViewModel)
        trackedViewModels.clear()
        idleMain()
    }

    @Test
    fun constructionIsInactiveAndActivateObtainsRuntimeExactlyOnce() {
        val gateway = FakeSubmissionGateway()
        var runtimeReads = 0
        val viewModel = viewModel {
            runtimeReads += 1
            gateway
        }

        assertEquals(AiSubmissionStatus.INACTIVE, viewModel.state.value.status)
        assertEquals(0, runtimeReads)

        viewModel.activate()
        viewModel.activate()
        idleMain()

        assertEquals(1, runtimeReads)
        assertEquals(AiSubmissionStatus.IDLE, viewModel.state.value.status)
    }

    @Test
    fun blankInputDoesNotReachGatewayAndUnicodeInternalWhitespaceIsPreserved() {
        val fixture = fixture()
        fixture.viewModel.activate()
        fixture.viewModel.updateDraft(" \n\t ")
        fixture.viewModel.submit(HEX_A)
        idleMain()

        assertTrue(fixture.gateway.submissions.isEmpty())
        assertEquals(" \n\t ", fixture.viewModel.state.value.draft)

        fixture.viewModel.updateDraft(" \n 你好 🌏\nline 2\t ")
        fixture.viewModel.submit(HEX_A)
        idleMain()

        assertEquals(listOf(HEX_A to "你好 🌏\nline 2"), fixture.gateway.submissions)
    }

    @Test
    fun acceptedClearsDraftWhileBusyUnconfiguredAndPreflightFailurePreserveIt() {
        listOf(
            AiSubmissionDecision.ACCEPTED to "",
            AiSubmissionDecision.BUSY to "keep busy",
            AiSubmissionDecision.UNCONFIGURED to "keep unconfigured",
            AiSubmissionDecision.REJECTED to "keep rejected",
            AiSubmissionDecision.SETTINGS_FAILURE to "keep settings failure",
            AiSubmissionDecision.SECRET_FAILURE to "keep secret failure"
        ).forEach { (decision, expectedDraft) ->
            val fixture = fixture(decision)
            fixture.viewModel.activate()
            fixture.viewModel.updateDraft(
                if (expectedDraft.isEmpty()) "accepted" else expectedDraft
            )
            fixture.viewModel.submit(HEX_A)
            idleMain()

            assertEquals(expectedDraft, fixture.viewModel.state.value.draft)
        }
    }

    @Test
    fun operationGuardMakesRapidDoubleClickOneSubmission() {
        val fixture = fixture(AiSubmissionDecision.ACCEPTED, autoCompleteSubmit = false)
        fixture.viewModel.activate()
        fixture.viewModel.updateDraft("send once")

        fixture.viewModel.submit(HEX_A)
        fixture.viewModel.submit(HEX_A)
        idleMain()

        assertEquals(listOf(HEX_A to "send once"), fixture.gateway.submissions)
        fixture.gateway.finishSubmit(AiSubmissionDecision.ACCEPTED)
        idleMain()
        assertEquals("", fixture.viewModel.state.value.draft)
    }

    @Test
    fun coordinatorWorkingIdleFailureCancellationAndDismissMapToFiniteUiStates() {
        val fixture = fixture()
        fixture.viewModel.activate()

        fixture.gateway.coordinatorState.value = AiCoordinatorState.Working(HEX_A)
        idleMain()
        assertEquals(AiSubmissionStatus.WORKING, fixture.viewModel.state.value.status)

        fixture.gateway.coordinatorState.value = AiCoordinatorState.Idle
        idleMain()
        assertEquals(AiSubmissionStatus.COMPLETED, fixture.viewModel.state.value.status)

        fixture.gateway.coordinatorState.value =
            AiCoordinatorState.Failed(AiCoordinatorError.TIMEOUT)
        idleMain()
        assertEquals(AiSubmissionStatus.FAILED, fixture.viewModel.state.value.status)
        assertEquals(AiCoordinatorError.TIMEOUT, fixture.viewModel.state.value.errorCode)

        fixture.viewModel.dismissStatus()
        idleMain()
        assertEquals(AiSubmissionStatus.IDLE, fixture.viewModel.state.value.status)
        assertEquals(1, fixture.gateway.acknowledgements)

        fixture.gateway.coordinatorState.value = AiCoordinatorState.Cancelled
        idleMain()
        assertEquals(AiSubmissionStatus.CANCELLED, fixture.viewModel.state.value.status)
    }

    @Test
    fun dismissDuringWorkingNeverAcknowledgesOrCancelsAppScopedRequest() {
        val fixture = fixture()
        fixture.viewModel.activate()
        fixture.gateway.coordinatorState.value = AiCoordinatorState.Working(HEX_A)
        idleMain()

        fixture.viewModel.dismissStatus()
        idleMain()

        assertEquals(AiSubmissionStatus.WORKING, fixture.viewModel.state.value.status)
        assertEquals(0, fixture.gateway.acknowledgements)
        assertFalse(fixture.gateway.cancelled)
    }

    @Test
    fun recreatedViewModelObservesSameAppRequestWithoutCancellingOrResubmitting() {
        val gateway = FakeSubmissionGateway()
        val first = viewModel { gateway }
        first.activate()
        first.updateDraft("captured request")
        first.submit(HEX_A)
        idleMain()
        gateway.coordinatorState.value = AiCoordinatorState.Working(HEX_A)
        idleMain()

        clearViewModel(first)
        trackedViewModels.remove(first)
        val recreated = viewModel { gateway }
        recreated.activate()
        idleMain()

        assertEquals(AiSubmissionStatus.WORKING, recreated.state.value.status)
        assertEquals(listOf(HEX_A to "captured request"), gateway.submissions)
        assertFalse(gateway.cancelled)

        gateway.coordinatorState.value = AiCoordinatorState.Idle
        idleMain()
        assertEquals(AiSubmissionStatus.COMPLETED, recreated.state.value.status)
    }

    private fun fixture(
        decision: AiSubmissionDecision = AiSubmissionDecision.ACCEPTED,
        autoCompleteSubmit: Boolean = true
    ): Fixture {
        val gateway = FakeSubmissionGateway(decision, autoCompleteSubmit)
        return Fixture(gateway, viewModel { gateway })
    }

    private fun viewModel(provider: () -> AiSubmissionGateway): AiSubmissionViewModel =
        AiSubmissionViewModel(provider).also(trackedViewModels::add)

    private fun clearViewModel(viewModel: AiSubmissionViewModel) {
        ViewModel::class.java.declaredMethods.single {
            it.name.startsWith("clear") && it.parameterCount == 0
        }.also {
            it.isAccessible = true
        }.invoke(viewModel)
    }

    private fun idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private data class Fixture(
        val gateway: FakeSubmissionGateway,
        val viewModel: AiSubmissionViewModel
    )

    private class FakeSubmissionGateway(
        private var decision: AiSubmissionDecision = AiSubmissionDecision.ACCEPTED,
        private val autoCompleteSubmit: Boolean = true
    ) : AiSubmissionGateway {
        val coordinatorState = MutableStateFlow<AiCoordinatorState>(AiCoordinatorState.Idle)
        override val state: StateFlow<AiCoordinatorState> = coordinatorState
        val submissions = mutableListOf<Pair<String, String>>()
        var acknowledgements = 0
        var cancelled = false
        private var pending: ((AiSubmissionDecision) -> Unit)? = null

        override suspend fun submit(conversationId: String, message: String): AiSubmissionDecision {
            submissions += conversationId to message
            if (autoCompleteSubmit) return decision
            return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                pending = { value -> continuation.resume(value) }
            }
        }

        fun finishSubmit(value: AiSubmissionDecision) {
            pending?.invoke(value)
            pending = null
        }

        override fun acknowledgeTerminal() {
            acknowledgements += 1
            coordinatorState.value = AiCoordinatorState.Idle
        }
    }

    private companion object {
        const val HEX_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
