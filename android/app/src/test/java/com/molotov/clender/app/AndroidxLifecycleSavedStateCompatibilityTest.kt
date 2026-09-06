package com.molotov.clender.app

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AndroidxLifecycleSavedStateCompatibilityTest {
    @Test
    fun lifecycleRegistryPreservesOrderedTransitionsThroughDestroyedState() {
        val owner = RegistryOwner()

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }

    @Test
    fun savedStateBundleRoundTripsUnicodeAndIntegerBoundariesOnce() {
        val original = SavedStateOwner().apply { restore(null) }
        original.savedStateRegistry.registerSavedStateProvider("fixture") {
            Bundle().apply {
                putString("unicode", "日程-📅")
                putInt("minimum", Int.MIN_VALUE)
                putInt("maximum", Int.MAX_VALUE)
            }
        }
        val outer = Bundle()
        original.save(outer)

        val restored = SavedStateOwner().apply { restore(outer) }
        val value = restored.savedStateRegistry.consumeRestoredStateForKey("fixture")

        assertEquals("日程-📅", value?.getString("unicode"))
        assertEquals(Int.MIN_VALUE, value?.getInt("minimum"))
        assertEquals(Int.MAX_VALUE, value?.getInt("maximum"))
        assertNull(restored.savedStateRegistry.consumeRestoredStateForKey("fixture"))
        assertNull(restored.savedStateRegistry.consumeRestoredStateForKey("missing"))
    }

    @Test
    fun savedStateRejectsDuplicateProviderAndConsumptionBeforeRestore() {
        val owner = SavedStateOwner()
        owner.savedStateRegistry.registerSavedStateProvider("duplicate") { Bundle() }

        assertThrows(IllegalArgumentException::class.java) {
            owner.savedStateRegistry.registerSavedStateProvider("duplicate") { Bundle() }
        }
        assertThrows(IllegalStateException::class.java) {
            owner.savedStateRegistry.consumeRestoredStateForKey("duplicate")
        }
    }
}

private class RegistryOwner : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this)

    override val lifecycle: Lifecycle
        get() = registry
}

private class SavedStateOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = registry

    override val savedStateRegistry: SavedStateRegistry
        get() = controller.savedStateRegistry

    fun restore(savedState: Bundle?) {
        controller.performAttach()
        controller.performRestore(savedState)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun save(outBundle: Bundle) {
        controller.performSave(outBundle)
    }
}
