package com.molotov.clender.app.widget

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = Application::class)
class WidgetProcessLifecycleBindingTest {
    @Test
    fun eachForegroundRequestsOnceAndBackgroundDoesNotRequest() {
        val owner = Owner()
        var calls = 0
        val binding = WidgetProcessLifecycleBinding(owner.lifecycle) { calls++ }
        try {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            assertEquals(1, calls)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            assertEquals(1, calls)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            assertEquals(2, calls)
        } finally {
            binding.close()
        }
    }

    @Test
    fun closingTwiceRemovesObserverAndRejectsLaterForeground() {
        val owner = Owner()
        var calls = 0
        val binding = WidgetProcessLifecycleBinding(owner.lifecycle) { calls++ }
        assertEquals(1, owner.registry.observerCount)
        binding.close()
        binding.close()
        assertEquals(0, owner.registry.observerCount)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(0, calls)
    }

    @Test
    fun bindingToAlreadyStartedLifecycleRequestsCurrentForegroundOnce() {
        val owner = Owner()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        var calls = 0
        val binding = WidgetProcessLifecycleBinding(owner.lifecycle) { calls++ }
        try {
            assertEquals(1, calls)
        } finally {
            binding.close()
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }
}
