package com.molotov.clender.ui.theme

import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundRecreationTest {
    @Test
    fun blockedImportSurvivesRecreateAndRejectsSecondMutation() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        try {
            val model = backgroundViewModel(activity.get())
            waitUntil { !model.controller.busy }
            val uri = Uri.parse("content://background-fixture/photo")
            Shadows.shadowOf(
                activity.get().contentResolver
            ).registerInputStream(uri, blockedPng(entered, released))
            model.controller.choose(activity.get().contentResolver, uri)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            activity.recreate()
            val retained = backgroundViewModel(activity.get())
            assertSame(model, retained)
            assertTrue(retained.controller.busy)
            retained.controller.remove()
            released.countDown()
            waitUntil { !retained.controller.busy }
            assertFalse(retained.controller.failed)
            assertNotNull(retained.controller.selection.fileName)
            assertNotNull(retained.controller.bitmap)
        } finally {
            released.countDown()
            activity.pause().stop().destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun blockedPng(
        entered: CountDownLatch,
        released: CountDownLatch
    ): ByteArrayInputStream {
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        val data = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        bitmap.recycle()
        return object : ByteArrayInputStream(data) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                check(released.await(5, TimeUnit.SECONDS))
                return super.read(buffer, offset, length)
            }
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(predicate())
    }
}
