package com.molotov.clender.app.widget

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/** Bound and synchronously detached on main; background shutdown never needs main dispatch. */
class WidgetProcessLifecycleBinding(private val lifecycle: Lifecycle, onForeground: () -> Unit) :
    AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (!closed.get()) onForeground()
        }
    }

    init {
        lifecycle.addObserver(observer)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) lifecycle.removeObserver(observer)
    }
}
