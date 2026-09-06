package com.molotov.clender.app.ai

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class AiProcessLifecycleBindingTest {
    @Test
    fun processStopCancelsExactlyOnceAndClosedBindingNoLongerObserves() {
        lateinit var registry: LifecycleRegistry
        val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle
                get() = registry
        }
        registry = LifecycleRegistry.createUnsafe(owner)
        val controller = RecordingVisibilityController()
        val binding = AiProcessLifecycleBinding(
            registry,
            controller
        )

        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals(1, controller.foregrounds)
        assertEquals(1, controller.backgrounds)

        binding.close()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals(1, controller.foregrounds)
        assertEquals(1, controller.backgrounds)
    }
}

private class RecordingVisibilityController : ApplicationVisibilityController {
    var foregrounds = 0
    var backgrounds = 0

    override fun onAppForegrounded() {
        foregrounds += 1
    }

    override fun onAppBackgrounded() {
        backgrounds += 1
    }
}
