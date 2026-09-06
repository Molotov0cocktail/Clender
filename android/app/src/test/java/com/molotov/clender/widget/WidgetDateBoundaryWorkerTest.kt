package com.molotov.clender.widget

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.molotov.clender.app.widget.WidgetRefreshCompletion
import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = WidgetPlatformTestApplication::class)
class WidgetDateBoundaryWorkerTest {
    private val application: WidgetPlatformTestApplication
        get() = RuntimeEnvironment.getApplication() as WidgetPlatformTestApplication

    @After
    fun closeOwner() {
        application.ownerJob.cancel()
    }

    @Test
    fun localTerminalResultsNeverRequestAutomaticRetry() = runBlocking {
        val successes = setOf(
            WidgetRefreshCompletion.COMPLETED,
            WidgetRefreshCompletion.NO_WIDGETS,
            WidgetRefreshCompletion.SUPERSEDED
        )
        WidgetRefreshCompletion.entries.forEach { completion ->
            application.port = PlatformRecordingRefreshPort()
            application.port.completion.complete(completion)
            val actual = worker().doWork()
            val expected = if (completion in successes) {
                ListenableWorker.Result.success()
            } else {
                ListenableWorker.Result.failure()
            }
            assertEquals(expected, actual)
            assertEquals(
                listOf(WidgetRefreshTrigger.DATE_BOUNDARY to null),
                application.port.requests
            )
        }
    }

    @Test
    fun workerAwaitCancellationDoesNotCancelAcceptedSharedCompletion() = runBlocking {
        val awaiting = launch(start = CoroutineStart.UNDISPATCHED) { worker().doWork() }
        assertTrue(application.port.awaitEntered.isCompleted)
        awaiting.cancel()
        awaiting.join()
        assertTrue(awaiting.isCancelled)
        assertTrue(application.port.awaitCancelled.isCompleted)
        assertFalse(application.port.completion.isCancelled)
    }

    @Test
    fun unexpectedInputIsRejectedBeforeRuntimeAccess() = runBlocking {
        val actual = worker(Data.Builder().putString("payload", "untrusted").build()).doWork()
        assertEquals(ListenableWorker.Result.failure(), actual)
        assertEquals(0, application.runtimeReads)
    }

    private fun worker(input: Data = Data.EMPTY): WidgetDateBoundaryWorker =
        TestListenableWorkerBuilder.from(application, WidgetDateBoundaryWorker::class.java)
            .setInputData(input).build()
}
