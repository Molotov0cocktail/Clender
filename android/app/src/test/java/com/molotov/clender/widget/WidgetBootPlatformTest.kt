package com.molotov.clender.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Looper
import com.molotov.clender.app.widget.WidgetRefreshCompletion
import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import java.time.Duration
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
    application = WidgetPlatformTestApplication::class,
    shadows = [CountingBootPendingResult::class]
)
class WidgetBootPlatformTest {
    private val application: WidgetPlatformTestApplication
        get() = RuntimeEnvironment.getApplication() as WidgetPlatformTestApplication

    @After
    fun closeOwner() {
        application.ownerJob.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun invalidEnvelopeNeverReadsRuntimeOrStartsAsyncWork() {
        listOf(
            Intent(),
            Intent(Intent.ACTION_MY_PACKAGE_REPLACED),
            Intent(Intent.ACTION_BOOT_COMPLETED).putExtra("payload", "untrusted"),
            Intent(Intent.ACTION_BOOT_COMPLETED).setData(Uri.parse("clender-internal://boot")),
            Intent(Intent.ACTION_BOOT_COMPLETED).addCategory(Intent.CATEGORY_DEFAULT)
        ).forEach { intent ->
            val receiver = WidgetBootReceiver()
            receiver.onReceive(application, intent)
            assertFalse(shadowOf(receiver).wentAsync())
        }
        assertEquals(0, application.runtimeReads)
        assertTrue(application.port.requests.isEmpty())
    }

    @Test
    fun validBootWaitsForReceiptThenFinishesExactlyOnce() {
        val pending = receiveBoot()
        assertEquals(listOf(WidgetRefreshTrigger.BOOT to null), application.port.requests)
        assertEquals(0, pending.finishes)
        application.port.completion.complete(WidgetRefreshCompletion.COMPLETED)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, pending.finishes)
        application.ownerJob.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, pending.finishes)
    }

    @Test
    fun actualSystemBootShapeAcceptsUserHandleWithoutRoutingByItsValue() {
        listOf(0, 10, Int.MAX_VALUE).forEach { userId ->
            application.port = PlatformRecordingRefreshPort()
            val intent = Intent(Intent.ACTION_BOOT_COMPLETED).apply {
                putExtra(SYSTEM_USER_HANDLE, userId)
                flags = Intent.FLAG_RECEIVER_NO_ABORT or Intent.FLAG_RECEIVER_FOREGROUND
                component = ComponentName(application, WidgetBootReceiver::class.java)
                setPackage(application.packageName)
            }
            val pending = receiveBoot(intent)
            assertEquals(listOf(WidgetRefreshTrigger.BOOT to null), application.port.requests)
            application.port.completion.complete(WidgetRefreshCompletion.COMPLETED)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, pending.finishes)
        }
    }

    @Test
    fun userHandleMustBeTheOnlyExtraAndAnActualNonnegativeInt() {
        listOf(
            Intent(Intent.ACTION_BOOT_COMPLETED).putExtra(SYSTEM_USER_HANDLE, -1),
            Intent(Intent.ACTION_BOOT_COMPLETED).putExtra(SYSTEM_USER_HANDLE, 0L),
            Intent(Intent.ACTION_BOOT_COMPLETED).putExtra(SYSTEM_USER_HANDLE, "0"),
            Intent(Intent.ACTION_BOOT_COMPLETED).putExtra(SYSTEM_USER_HANDLE, true),
            Intent(Intent.ACTION_BOOT_COMPLETED).putExtra(SYSTEM_USER_HANDLE, null as String?),
            Intent(Intent.ACTION_BOOT_COMPLETED).putExtra(SYSTEM_USER_HANDLE, 0)
                .putExtra("payload", "untrusted")
        ).forEach { intent ->
            val receiver = WidgetBootReceiver()
            receiver.onReceive(application, intent)
            assertFalse(shadowOf(receiver).wentAsync())
        }
        assertEquals(0, application.runtimeReads)
        assertTrue(application.port.requests.isEmpty())
    }

    @Test
    fun nineSecondTimeoutFinishesOnceWithoutCancellingSharedReceipt() {
        val pending = receiveBoot()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8_999))
        assertEquals(0, pending.finishes)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, pending.finishes)
        assertTrue(application.port.awaitCancelled.isCompleted)
        assertFalse(application.port.completion.isCancelled)
        application.port.completion.complete(WidgetRefreshCompletion.COMPLETED)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, pending.finishes)
    }

    @Test
    fun alreadyCancelledOwnerStillFinishesExactlyOnce() {
        application.ownerJob.cancel()
        val pending = receiveBoot()
        assertEquals(1, pending.finishes)
        assertTrue(application.port.requests.isEmpty())
    }

    @Test
    fun receiptFailureStillFinishesWithoutExposingException() {
        val pending = receiveBoot()
        application.port.completion.completeExceptionally(
            IllegalStateException("synthetic failure")
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, pending.finishes)
    }

    private fun receiveBoot(
        intent: Intent = Intent(Intent.ACTION_BOOT_COMPLETED)
    ): CountingBootPendingResult {
        val receiver = WidgetBootReceiver()
        val pending = ReflectionHelpers.callConstructor(BroadcastReceiver.PendingResult::class.java)
        ReflectionHelpers.callInstanceMethod<Unit>(
            receiver,
            "setPendingResult",
            ClassParameter.from(BroadcastReceiver.PendingResult::class.java, pending)
        )
        receiver.onReceive(application, intent)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(receiver).wentAsync())
        return Shadow.extract(pending)
    }

    private companion object {
        const val SYSTEM_USER_HANDLE = "android.intent.extra.user_handle"
    }
}

@Implements(BroadcastReceiver.PendingResult::class)
class CountingBootPendingResult {
    var finishes = 0
        private set

    @Implementation
    fun finish() {
        finishes += 1
    }
}
