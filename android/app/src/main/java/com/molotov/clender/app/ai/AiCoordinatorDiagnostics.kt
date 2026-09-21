package com.molotov.clender.app.ai

import com.molotov.clender.diagnostics.DiagnosticEvent
import com.molotov.clender.diagnostics.DiagnosticLogger
import com.molotov.clender.diagnostics.DiagnosticPhase
import com.molotov.clender.diagnostics.DiagnosticRecord
import com.molotov.clender.diagnostics.DiagnosticResult

internal fun DiagnosticLogger.recordAiQueuedBudget(output: Int, context: Int) = recordSafely(
    DiagnosticRecord(
        event = DiagnosticEvent.AI_BUDGET,
        phase = DiagnosticPhase.QUEUED,
        outputTokens = output.toLong().takeIf { it > 0 },
        contextTokens = context.toLong().takeIf { it > 0 }
    )
)

internal fun DiagnosticLogger.recordAiBudget(input: Int, output: Int, context: Int) = recordSafely(
    DiagnosticRecord(
        event = DiagnosticEvent.AI_BUDGET,
        phase = DiagnosticPhase.VALIDATE,
        result = DiagnosticResult.SUCCESS,
        inputTokens = input.toLong(),
        outputTokens = output.toLong(),
        contextTokens = context.toLong()
    )
)

internal fun DiagnosticLogger.recordAiExecution(success: Boolean) = recordSafely(
    DiagnosticRecord(
        event = DiagnosticEvent.AI_EXECUTION,
        phase = DiagnosticPhase.EXECUTE,
        result = if (success) DiagnosticResult.SUCCESS else DiagnosticResult.FAILURE
    )
)

internal fun DiagnosticLogger.recordAiTerminal(state: AiCoordinatorState, elapsedMs: Long) =
    recordSafely(
        DiagnosticRecord(
            event = DiagnosticEvent.AI_EXECUTION,
            phase = DiagnosticPhase.COMPLETE,
            result = when (state) {
                AiCoordinatorState.Idle -> DiagnosticResult.SUCCESS
                AiCoordinatorState.Cancelled -> DiagnosticResult.CANCELLED
                is AiCoordinatorState.Failed -> state.error.diagnosticResult()
                is AiCoordinatorState.Working -> DiagnosticResult.NONE
            },
            elapsedMs = elapsedMs
        )
    )

private fun AiCoordinatorError.diagnosticResult(): DiagnosticResult = when (this) {
    AiCoordinatorError.CONFIGURATION -> DiagnosticResult.CONFIGURATION_ERROR

    AiCoordinatorError.TIMEOUT -> DiagnosticResult.TIMEOUT

    AiCoordinatorError.NETWORK -> DiagnosticResult.NETWORK_ERROR

    AiCoordinatorError.CONTEXT_BUDGET -> DiagnosticResult.CONTEXT_LIMIT

    AiCoordinatorError.OUTPUT_LIMIT -> DiagnosticResult.OUTPUT_LIMIT

    AiCoordinatorError.INVALID_RESPONSE -> DiagnosticResult.INVALID_RESPONSE

    AiCoordinatorError.AUTHENTICATION,
    AiCoordinatorError.RATE_LIMIT,
    AiCoordinatorError.REQUEST_PARAMETERS,
    AiCoordinatorError.PROVIDER -> DiagnosticResult.HTTP_ERROR

    AiCoordinatorError.INTERNAL -> DiagnosticResult.FAILURE
}

private fun DiagnosticLogger.recordSafely(record: DiagnosticRecord) {
    runCatching { record(record) }
}
