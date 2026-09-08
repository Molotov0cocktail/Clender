package com.molotov.clender.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = Application::class)
class AppContainerDataStoreShutdownTest {
    @Test
    fun closeWaitsForCancelledDataStoreScopeCleanupBeforeReturning() {
        val container = AppContainer(ApplicationProvider.getApplicationContext<Application>())
        val field = AppContainer::class.java.getDeclaredField("dataStoreScope").apply {
            isAccessible =
                true
        }
        val scope = field.get(container) as CoroutineScope
        val scopeJob = requireNotNull(scope.coroutineContext[Job])
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val cleanupFinished = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val closeFailure = AtomicReference<Throwable?>()
        val child = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.countDown()
                    check(releaseCleanup.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    cleanupFinished.countDown()
                }
            }
        }
        val closer = Thread({
            try {
                container.close()
            } catch (failure: Throwable) {
                closeFailure.set(failure)
            } finally {
                closeReturned.countDown()
            }
        }, "datastore-close-ownership-test")
        try {
            closer.start()
            assertTrue(cleanupStarted.await(WAIT_SECONDS, TimeUnit.SECONDS))
            assertFalse(
                "close returned while the DataStore scope still owns pending cleanup",
                closeReturned.await(RETURN_PROBE_MILLIS, TimeUnit.MILLISECONDS)
            )
        } finally {
            releaseCleanup.countDown()
            closer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            runBlocking { child.join() }
            container.close()
        }
        closeFailure.get()?.let { throw AssertionError("close failed", it) }
        assertFalse("close thread leaked", closer.isAlive)
        assertTrue(cleanupFinished.count == 0L)
        assertTrue(
            "DataStore owner must finish before callers remove its directory",
            scopeJob.isCompleted
        )
    }
}

private const val WAIT_SECONDS = 10L
private const val RETURN_PROBE_MILLIS = 250L
