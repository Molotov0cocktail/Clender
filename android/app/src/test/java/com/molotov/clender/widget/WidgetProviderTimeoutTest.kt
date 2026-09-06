package com.molotov.clender.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.os.Bundle
import android.os.Looper
import com.molotov.clender.app.widget.WidgetRefreshCompletion
import java.time.Duration
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [26, 36],
    application = WidgetProviderTimeoutApplication::class,
    shadows = [CountingProviderPendingResult::class]
)
class WidgetProviderTimeoutTest {
    private val application: WidgetProviderTimeoutApplication
        get() = RuntimeEnvironment.getApplication() as WidgetProviderTimeoutApplication

    @After
    fun closeIndependentRuntimeAndDrainCallbacks() {
        application.automatic.close()
        application.ownerJob.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun updateTimeoutFinishesOnceAndAcceptedRuntimeWorkSurvives() {
        assertTimeout(Callback.UPDATE)
    }

    @Test
    fun optionsTimeoutFinishesOnceAndAcceptedRuntimeWorkSurvives() {
        assertTimeout(Callback.OPTIONS)
    }

    @Test
    fun deletionTimeoutFinishesOnceAndAcceptedRuntimeWorkSurvives() {
        assertTimeout(Callback.DELETE)
        assertEquals(1, application.invalidations)
    }

    @Test
    fun queuedReceiptWaitCountsTowardNineSecondBroadcastLimit() {
        val first = application.automatic.restore()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, application.workEntered)
        val pending = receive(Callback.UPDATE)
        assertEquals(1, application.workEntered)
        expireWait(pending)
        application.release.complete(Unit)
        var firstFinished = false
        application.widgetProviderScope.launch {
            first.await()
            firstFinished = true
        }
        assertSharedReceiptCompletes(pending)
        assertTrue(firstFinished)
        assertEquals(2, application.workCompleted)
    }

    @Test
    fun successfulCompletionFinishesOnceEvenAfterDeadlineAndOwnerCancellation() {
        val pending = receive(Callback.UPDATE)
        assertEquals(0, pending.finishes)
        application.release.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, pending.finishes)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        application.ownerJob.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, pending.finishes)
        assertFalse(application.waitCancelled)
    }

    @Test
    fun finiteRuntimeFailureFinishesExactlyOnce() {
        val pending = receive(Callback.OPTIONS)
        application.release.completeExceptionally(IllegalStateException("synthetic local failure"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, pending.finishes)
        var result: WidgetRefreshCompletion? = null
        application.widgetProviderScope.launch {
            result = requireNotNull(application.receipt).await()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WidgetRefreshCompletion.UPDATE_FAILED, result)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertEquals(1, pending.finishes)
    }

    @Test
    fun cancelledOwnerStillFinishesPendingResultExactlyOnce() {
        application.ownerJob.cancel()
        val pending = receive(Callback.UPDATE)
        assertEquals(1, pending.finishes)
        assertEquals(0, application.workEntered)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertEquals(1, pending.finishes)
    }

    private fun assertTimeout(callback: Callback) {
        val pending = receive(callback)
        assertEquals(1, application.workEntered)
        expireWait(pending)
        application.release.complete(Unit)
        assertSharedReceiptCompletes(pending)
        assertEquals(1, application.workCompleted)
    }

    private fun expireWait(pending: CountingProviderPendingResult) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8_999))
        assertEquals(0, pending.finishes)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, pending.finishes)
        assertTrue(application.waitCancelled)
        assertTrue(application.ownerJob.isActive)
        assertFalse(application.release.isCancelled)
        assertEquals(0, application.workCompleted)
        assertFalse(application.workCancelled)
    }

    private fun assertSharedReceiptCompletes(pending: CountingProviderPendingResult) {
        var result: WidgetRefreshCompletion? = null
        application.widgetProviderScope.launch {
            result = requireNotNull(application.receipt).await()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            result == WidgetRefreshCompletion.COMPLETED ||
                result == WidgetRefreshCompletion.NO_WIDGETS
        )
        assertFalse(application.workCancelled)
        assertEquals(1, pending.finishes)
    }

    private fun receive(callback: Callback): CountingProviderPendingResult {
        val provider = ClenderWidgetProvider()
        val pending = ReflectionHelpers.callConstructor(BroadcastReceiver.PendingResult::class.java)
        ReflectionHelpers.callInstanceMethod<Unit>(
            provider,
            "setPendingResult",
            ClassParameter.from(BroadcastReceiver.PendingResult::class.java, pending)
        )
        val manager = AppWidgetManager.getInstance(application)
        when (callback) {
            Callback.UPDATE -> provider.onUpdate(application, manager, intArrayOf(1))

            Callback.OPTIONS -> provider.onAppWidgetOptionsChanged(
                application,
                manager,
                1,
                Bundle()
            )

            Callback.DELETE -> provider.onDeleted(application, intArrayOf(1))
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(provider).wentAsync())
        return Shadow.extract(pending)
    }

    private enum class Callback {
        UPDATE,
        OPTIONS,
        DELETE
    }
}

@Implements(BroadcastReceiver.PendingResult::class)
class CountingProviderPendingResult {
    var finishes = 0
        private set

    @Implementation
    fun finish() {
        finishes += 1
    }
}
