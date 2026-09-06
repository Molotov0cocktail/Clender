package com.molotov.clender.widget

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import com.molotov.clender.app.widget.WidgetDateWorkPort
import com.molotov.clender.domain.widget.WidgetDateBoundarySchedule
import com.molotov.clender.domain.widget.WidgetRefreshPolicy
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WorkManagerWidgetDateScheduler(private val workManager: WorkManager) : WidgetDateWorkPort {
    private val operationMutex = Mutex()

    override suspend fun replace(schedule: WidgetDateBoundarySchedule) {
        val delayMillis = schedule.validatedDelayMillis()
        operationMutex.withLock {
            val request = OneTimeWorkRequestBuilder<WidgetDateBoundaryWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            workManager.enqueueUniqueWork(
                WidgetRefreshPolicy.DATE_BOUNDARY_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            ).awaitCommit()
        }
    }

    override suspend fun cancel() {
        operationMutex.withLock {
            workManager.cancelUniqueWork(WidgetRefreshPolicy.DATE_BOUNDARY_WORK_NAME).awaitCommit()
        }
    }
}

private fun WidgetDateBoundarySchedule.validatedDelayMillis(): Long {
    require(uniqueWorkName == WidgetRefreshPolicy.DATE_BOUNDARY_WORK_NAME)
    require(appWide && oneTime && replaceExisting)
    require(!delay.isNegative && !delay.isZero)
    return try {
        val wholeMillis = delay.toMillis()
        if (delay.minusMillis(wholeMillis).isZero) wholeMillis else Math.addExact(wholeMillis, 1)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("Date work delay is out of range")
    }
}

/** Await the durable operation; caller cancellation must not undo already submitted work. */
private suspend fun Operation.awaitCommit(): Unit = suspendCancellableCoroutine { continuation ->
    val future = result
    future.addListener(
        {
            try {
                future.get()
                continuation.resume(Unit)
            } catch (failure: ExecutionException) {
                continuation.resumeWithException(failure.cause ?: failure)
            } catch (failure: CancellationException) {
                continuation.resumeWithException(failure)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                continuation.resumeWithException(failure)
            }
        },
        Executor { it.run() }
    )
}
