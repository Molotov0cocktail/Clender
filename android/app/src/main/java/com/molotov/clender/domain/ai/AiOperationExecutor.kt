package com.molotov.clender.domain.ai

import com.molotov.clender.domain.event.EventService
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class AiOperationOutcome(
    val action: String,
    val success: Boolean,
    val message: String,
    val changed: Boolean = success && action != "reply"
)

data class AiExecutionReport(
    val outcomes: List<AiOperationOutcome>,
    val replies: List<String>,
    val scheduleChanged: Boolean
)

class AiOperationExecutor(private val eventService: EventService) {
    suspend fun execute(operations: List<AiOperation>): AiExecutionReport {
        val outcomes = mutableListOf<AiOperationOutcome>()
        val replies = mutableListOf<String>()
        var scheduleChanged = false
        eventService.runBatched { scoped ->
            for (operation in operations) {
                currentCoroutineContext().ensureActive()
                val outcome = try {
                    apply(operation, scoped, replies)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: RuntimeException) {
                    failure(operation)
                }
                outcomes += outcome
                scheduleChanged = scheduleChanged || outcome.changed
            }
        }
        return AiExecutionReport(outcomes, replies, scheduleChanged)
    }

    private suspend fun apply(
        operation: AiOperation,
        scoped: EventService,
        replies: MutableList<String>
    ): AiOperationOutcome = when (operation) {
        is AiOperation.Add -> {
            scoped.add(operation.command)
            success(operation)
        }

        is AiOperation.Update -> {
            val result = scoped.updateWithResult(operation.eventId, operation.patch)
            if (result.changed) {
                success(
                    operation
                )
            } else {
                AiOperationOutcome("update", true, "Already up to date", false)
            }
        }

        is AiOperation.Delete -> if (scoped.delete(
                operation.eventId
            )
        ) {
            success(operation)
        } else {
            failure(operation)
        }

        is AiOperation.Reply -> {
            replies += operation.message
            success(operation)
        }
    }

    private fun success(operation: AiOperation) = AiOperationOutcome(
        action = operation.actionName(),
        success = true,
        message = "Operation completed"
    )

    private fun failure(operation: AiOperation) = AiOperationOutcome(
        action = operation.actionName(),
        success = false,
        message = "Operation could not be applied"
    )
}

private fun AiOperation.actionName(): String = when (this) {
    is AiOperation.Add -> "add"
    is AiOperation.Update -> "update"
    is AiOperation.Delete -> "delete"
    is AiOperation.Reply -> "reply"
}
