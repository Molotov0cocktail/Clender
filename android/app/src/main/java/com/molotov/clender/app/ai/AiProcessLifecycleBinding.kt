package com.molotov.clender.app.ai

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

interface ApplicationVisibilityController {
    fun onAppForegrounded()

    fun onAppBackgrounded()
}

class AiProcessLifecycleBinding(
    private val lifecycle: Lifecycle,
    controller: ApplicationVisibilityController
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            controller.onAppForegrounded()
        }

        override fun onStop(owner: LifecycleOwner) {
            controller.onAppBackgrounded()
        }
    }

    init {
        lifecycle.addObserver(observer)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) lifecycle.removeObserver(observer)
    }
}
