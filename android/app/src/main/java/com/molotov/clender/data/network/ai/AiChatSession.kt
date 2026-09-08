package com.molotov.clender.data.network.ai

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class AiChatSession(val url: String, val authorization: String, timeout: Duration) {
    private val startedAt = System.nanoTime()
    private val timeoutNanos = timeout.toNanos()
    private val cancelled = AtomicBoolean(false)
    var includeExtensions = true

    fun cancel() {
        cancelled.set(true)
    }

    suspend fun ensureActive() {
        currentCoroutineContext().ensureActive()
        if (cancelled.get()) throw AiRequestCancelledException()
    }

    fun remaining(): Duration {
        val nanos = timeoutNanos - (System.nanoTime() - startedAt)
        if (nanos < NANOS_PER_MILLISECOND) throw AiTimeoutException()
        return Duration.ofNanos(nanos)
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
