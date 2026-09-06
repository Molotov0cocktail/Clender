package com.molotov.clender.app.sync

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

interface WebDavOperationLease : AutoCloseable

/**
 * App-scoped gate guaranteeing at most one WebDAV HTTP operation at a time.
 * Probes use [tryAcquire] (non-blocking); the sync worker uses [withExclusive]
 * (suspends until the current lease is released). Closing the gate fails any
 * still-waiting [withExclusive] call with [IllegalStateException] and
 * invalidates both entry points.
 *
 * Waiting is implemented with public [CompletableDeferred] pulses instead of
 * raw continuations so that every resume and every failure throw happens on a
 * regular coroutine body path (never inside a continuation block), which is
 * safe under any dispatcher including the blocked-event-loop test context.
 */
class WebDavOperationGate : AutoCloseable {
    private val lock = Any()
    private var active = false
    private var closed = false
    private val waitingPulses = mutableListOf<CompletableDeferred<Unit>>()

    fun tryAcquire(): WebDavOperationLease? = synchronized(lock) {
        if (closed || active) return@synchronized null
        active = true
        Lease()
    }

    override fun close() {
        val woken: List<CompletableDeferred<Unit>>
        synchronized(lock) {
            if (closed) return
            closed = true
            woken = waitingPulses.toList()
            waitingPulses.clear()
        }
        woken.forEach { it.complete(Unit) }
    }

    suspend fun <T> withExclusive(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            releaseExclusive()
        }
    }

    private suspend fun acquire() {
        while (true) {
            val pulse = CompletableDeferred<Unit>()
            val granted = synchronized(lock) {
                when {
                    closed -> throw IllegalStateException(GATE_CLOSED)

                    !active -> {
                        active = true
                        true
                    }

                    else -> {
                        waitingPulses += pulse
                        false
                    }
                }
            }
            if (granted) return
            try {
                pulse.await()
            } finally {
                synchronized(lock) { waitingPulses.remove(pulse) }
            }
        }
    }

    private fun releaseExclusive() {
        val pulse: CompletableDeferred<Unit>?
        synchronized(lock) {
            active = false
            pulse = waitingPulses.firstOrNull()
            if (pulse != null) waitingPulses.remove(pulse)
        }
        pulse?.complete(Unit)
    }

    private inner class Lease : WebDavOperationLease {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            releaseExclusive()
        }
    }

    private companion object {
        const val GATE_CLOSED = "WebDAV operation gate is closed"
    }
}
