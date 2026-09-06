package com.molotov.clender.domain.ai

import com.molotov.clender.domain.event.EventService
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class AiOperationOutcome(val action: String, val success: Boolean, val message: String)

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
                try {
                    when (operation) {
                        is AiOperation.Add -> {
                            scoped.add(operation.command)
                            scheduleChanged = true
                        }

                        is AiOperation.Update -> {
                            scoped.update(operation.eventId, operation.patch)
                            scheduleChanged = true
                        }

                        is AiOperation.Delete -> {
                            if (!scoped.delete(operation.eventId)) {
                                outcomes += failure(operation)
                                continue
                            }
                            scheduleChanged = true
                        }

                        is AiOperation.Reply -> replies += operation.message
                    }
                    outcomes += success(operation)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: RuntimeException) {
                    outcomes += failure(operation)
                }
            }
        }
        return AiExecutionReport(outcomes, replies, scheduleChanged)
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
