package com.molotov.clender.app.ai

import java.util.concurrent.atomic.AtomicBoolean

interface AiOperationLease : AutoCloseable

class AiOperationGate : AutoCloseable {
    private val lock = Any()
    private var activeToken: Any? = null
    private var closed = false

    fun tryAcquire(): AiOperationLease? = synchronized(lock) {
        if (closed || activeToken != null) return@synchronized null
        val token = Any()
        activeToken = token
        GateLease(token)
    }

    override fun close() {
        synchronized(lock) {
            closed = true
        }
    }

    private inner class GateLease(private val token: Any) : AiOperationLease {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            synchronized(lock) {
                if (activeToken === token) activeToken = null
            }
        }
    }
}
