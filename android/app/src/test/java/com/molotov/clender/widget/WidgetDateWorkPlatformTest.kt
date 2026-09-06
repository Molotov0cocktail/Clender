package com.molotov.clender.widget

import android.os.Looper
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.molotov.clender.domain.widget.WidgetDateBoundarySchedule
import com.molotov.clender.domain.widget.WidgetRefreshPolicy
import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = WidgetPlatformTestApplication::class)
class WidgetDateWorkPlatformTest {
    private val application: WidgetPlatformTestApplication
        get() = RuntimeEnvironment.getApplication() as WidgetPlatformTestApplication
    private lateinit var manager: WorkManager
    private lateinit var scheduler: WorkManagerWidgetDateScheduler

    @Before
    fun initializeActualWorkManager() {
        val configuration = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setWorkerCoroutineContext(Dispatchers.Unconfined)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(application, configuration)
        manager = WorkManager.getInstance(application)
        scheduler = WorkManagerWidgetDateScheduler(manager)
    }

    @After
    fun releaseWorkDatabaseAndOwner() {
        application.port.close()
        application.ownerJob.cancel()
        manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun replacePersistsOneTimeEmptyInputExactDelayBeforeReturning() = runBlocking {
        scheduler.replace(schedule(Duration.ofHours(23)))
        val first = unfinished().single()
        val spec = requireNotNull(
            (manager as WorkManagerImpl).workDatabase.workSpecDao().getWorkSpec(first.id.toString())
        )
        assertEquals(WidgetDateBoundaryWorker::class.java.name, spec.workerClassName)
        assertEquals(Duration.ofHours(23).toMillis(), spec.initialDelay)
        assertEquals(0L, spec.intervalDuration)
        assertFalse(spec.expedited)
        assertEquals(Data.EMPTY, spec.input)
        assertFalse(spec.hasConstraints())

        scheduler.replace(schedule(Duration.ofHours(25)))
        val successor = unfinished().single()
        assertTrue(first.id != successor.id)
        assertReplaced(first.id)
        scheduler.cancel()
        assertTrue(unfinished().isEmpty())
    }

    @Test
    fun runningWorkerReplacementCancellationLeavesPersistedSuccessor() = runBlocking {
        scheduler.replace(schedule(Duration.ofSeconds(1)))
        val original = unfinished().single()
        requireNotNull(WorkManagerTestInitHelper.getTestDriver(application))
            .setInitialDelayMet(original.id)
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(5_000) { application.port.awaitEntered.await() }
        assertEquals(WorkInfo.State.RUNNING, workInfo(original.id).state)
        assertEquals(listOf(WidgetRefreshTrigger.DATE_BOUNDARY to null), application.port.requests)

        val persisted = CompletableDeferred<Unit>()
        application.widgetAutomaticRefreshScope.launch {
            scheduler.replace(schedule(Duration.ofHours(24)))
            persisted.complete(Unit)
        }
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(5_000) {
            persisted.await()
            application.port.awaitCancelled.await()
        }
        assertFalse(application.port.completion.isCancelled)
        assertReplaced(original.id)
        val successor = unfinished().single()
        assertTrue(successor.id != original.id)
        assertEquals(WorkInfo.State.ENQUEUED, successor.state)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(successor.id, unfinished().single().id)
    }

    @Test
    fun malformedScheduleIsRejectedWithoutEnqueue() = runBlocking {
        val valid = schedule(Duration.ofHours(1))
        val invalid = listOf(
            valid.copy(uniqueWorkName = "other-work"),
            valid.copy(appWide = false),
            valid.copy(oneTime = false),
            valid.copy(replaceExisting = false),
            valid.copy(delay = Duration.ZERO),
            valid.copy(delay = Duration.ofMillis(-1))
        )
        invalid.forEach { candidate ->
            val result = runCatching { scheduler.replace(candidate) }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertTrue(unfinished().isEmpty())
        }
    }

    private fun unfinished(): List<WorkInfo> = manager.getWorkInfosForUniqueWork(
        WidgetRefreshPolicy.DATE_BOUNDARY_WORK_NAME
    ).get(5, TimeUnit.SECONDS).filterNot { it.state.isFinished }

    private fun workInfo(id: UUID): WorkInfo =
        requireNotNull(manager.getWorkInfoById(id).get(5, TimeUnit.SECONDS))

    private fun assertReplaced(id: UUID) {
        // WorkManager REPLACE cancels through Processor and deletes the old WorkSpec.
        assertTrue((manager as WorkManagerImpl).processor.isCancelled(id.toString()))
        assertNull(manager.getWorkInfoById(id).get(5, TimeUnit.SECONDS))
    }

    private fun schedule(delay: Duration) = WidgetDateBoundarySchedule(
        WidgetRefreshPolicy.DATE_BOUNDARY_WORK_NAME,
        appWide = true,
        oneTime = true,
        replaceExisting = true,
        delay = delay
    )
}
