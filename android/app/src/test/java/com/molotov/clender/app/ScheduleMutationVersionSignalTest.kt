package com.molotov.clender.app

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.domain.event.ScheduleMutation
import com.molotov.clender.domain.event.ScheduleMutationSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleMutationVersionSignalTest {
    @Test
    fun startsAtZeroAndExposesOnlyAReadOnlyLongStateFlow() {
        val signal = ScheduleMutationVersionSignal()
        val sink: ScheduleMutationSink = signal
        val version: StateFlow<Long> = signal.version

        assertEquals(0L, version.value)
        assertTrue(sink is ScheduleMutationVersionSignal)
    }

    @Test
    fun everySinkCallbackIncrementsExactlyOnceIncludingBatch() {
        val signal = ScheduleMutationVersionSignal()
        val event = eventFixture()
        val callbacks = listOf(
            ScheduleMutation.Added(event),
            ScheduleMutation.Updated(event.copy(title = "updated")),
            ScheduleMutation.Deleted(event.copy(deletedAt = event.updatedAt)),
            ScheduleMutation.Batch(
                listOf(
                    ScheduleMutation.Added(event),
                    ScheduleMutation.Updated(event)
                )
            )
        )

        callbacks.forEachIndexed { index, mutation ->
            signal.onMutation(mutation)
            assertEquals(index + 1L, signal.version.value)
        }
    }

    @Test
    fun concurrentCallbacksIncrementAtomicallyWithoutLosingVersions() {
        val signal = ScheduleMutationVersionSignal()
        val mutation = ScheduleMutation.Added(eventFixture())
        val workers = 8
        val callbacksPerWorker = 250
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val executor = Executors.newFixedThreadPool(workers)

        try {
            repeat(workers) {
                executor.execute {
                    ready.countDown()
                    start.await()
                    repeat(callbacksPerWorker) { signal.onMutation(mutation) }
                    done.countDown()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))

            assertEquals((workers * callbacksPerWorker).toLong(), signal.version.value)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun publicSignalTypeCannotExposeEventOrMutationBodies() {
        val signal = ScheduleMutationVersionSignal()
        val exposed: StateFlow<Long> = signal.version
        val publicValue: Any = exposed.value

        assertEquals(Long::class.javaObjectType, exposed.value.javaClass)
        assertTrue(publicValue !is Event)
        assertTrue(publicValue !is ScheduleMutation)
    }
}
