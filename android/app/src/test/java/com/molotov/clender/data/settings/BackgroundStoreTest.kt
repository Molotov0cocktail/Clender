package com.molotov.clender.data.settings

import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun importsRestoresStrengthAndRemovesOnlyOwnedImage() {
        val root = temporary.newFolder()
        val store = BackgroundStore(root)
        val unrelated = File(root, "keep.txt").apply { writeText("fixture") }
        val selected = store.importImage { ByteArrayInputStream(png()) }
        assertNotNull(selected.fileName)
        store.saveStrength(100)
        assertEquals(100, BackgroundStore(root).read().strength)
        assertNotNull(store.loadBitmap(store.read()))
        store.remove()
        assertNull(store.read().fileName)
        assertEquals("fixture", unrelated.readText())
    }

    @Test
    fun badImageCannotReplaceExistingSelection() {
        val store = BackgroundStore(temporary.newFolder())
        val original = store.importImage { ByteArrayInputStream(png()) }
        assertThrows(IllegalArgumentException::class.java) {
            store.importImage { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        }
        assertEquals(original, store.read())
    }

    @Test
    fun invalidConfigurationAndMissingImageFallBack() {
        val root = temporary.newFolder()
        File(root, "settings.json").writeText("{bad")
        val store = BackgroundStore(root)
        assertEquals(BackgroundSelection(), store.read())
        File(root, "settings.json").writeText("{\"file\":\"../private.png\",\"strength\":101}")
        assertNull(store.read().fileName)
        assertEquals(35, store.read().strength)
        assertNull(store.loadBitmap(BackgroundSelection("missing.png", 35)))
    }

    @Test
    fun oversizedInputAndEmptyStreamPreservePriorSelection() {
        val store = BackgroundStore(temporary.newFolder())
        val original = store.importImage { ByteArrayInputStream(png()) }
        for (bytes in listOf(byteArrayOf(), ByteArray(BackgroundPolicy.MAX_INPUT_BYTES + 1))) {
            assertThrows(IllegalArgumentException::class.java) {
                store.importImage { ByteArrayInputStream(bytes) }
            }
            assertEquals(original, store.read())
        }
    }

    @Test
    fun settingsReadIsBoundedAndRejectsNonIntegerStrength() {
        val root = temporary.newFolder()
        val file = File(root, "settings.json")
        val store = BackgroundStore(root)
        for (json in listOf("x".repeat(5000), "{\"strength\":\"100\"}", "{\"strength\":1.5}")) {
            file.writeText(json)
            assertEquals(BackgroundSelection(), store.read())
        }
    }

    @Test
    fun normalizedPhotoAppliesExifRotation() {
        val root = temporary.newFolder()
        val jpeg = temporary.newFile("photo.jpg")
        val jpegBytes = requireNotNull(javaClass.getResourceAsStream("/background-photo.jpg"))
            .use { it.readBytes() }
        // APP1 Exif: little-endian TIFF IFD containing orientation = 6 (clockwise 90 degrees).
        val exif = byteArrayOf(
            -1, -31, 0, 34, 69, 120, 105, 102, 0, 0, 73, 73, 42, 0, 8, 0, 0, 0,
            1, 0, 18, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0
        )
        jpeg.writeBytes(
            jpegBytes.copyOfRange(0, 2) + exif + jpegBytes.copyOfRange(2, jpegBytes.size)
        )
        assertEquals(
            "EXIF reader must preserve orientation",
            6,
            BackgroundImageDecoder.readOrientation(jpeg.readBytes())
        )
        val store = BackgroundStore(root)
        val selection = store.importImage { jpeg.inputStream() }
        val restored = requireNotNull(store.loadBitmap(selection))
        assertEquals(20, restored.width)
        assertEquals(40, restored.height)
        restored.recycle()
    }

    @Test
    fun allExifTransformsPreserveDimensionsAndCornerPixels() {
        // Exercise all production transforms separately on both Android APIs.
        val corners = listOf(
            android.graphics.Color.RED,
            android.graphics.Color.GREEN,
            android.graphics.Color.YELLOW,
            android.graphics.Color.BLUE,
            android.graphics.Color.RED,
            android.graphics.Color.BLUE,
            android.graphics.Color.YELLOW,
            android.graphics.Color.GREEN
        )
        for (orientation in 1..8) {
            val original = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
            original.setPixel(0, 0, android.graphics.Color.RED)
            original.setPixel(3, 0, android.graphics.Color.GREEN)
            original.setPixel(0, 1, android.graphics.Color.BLUE)
            original.setPixel(3, 1, android.graphics.Color.YELLOW)
            val transformed = BackgroundImageDecoder.applyOrientation(original, orientation)
            assertEquals(if (orientation < 5) 4 else 2, transformed.width)
            assertEquals(if (orientation < 5) 2 else 4, transformed.height)
            assertEquals(
                "Orientation $orientation",
                corners[orientation - 1],
                transformed.getPixel(0, 0)
            )
            transformed.recycle()
        }
    }

    @Test
    fun imageWriteFailureLeavesPreviousConfigAndImageIntact() {
        val root = temporary.newFolder()
        val store = BackgroundStore(root)
        val selection = store.importImage { ByteArrayInputStream(png()) }
        val settings = File(root, "settings.json")
        val saved = settings.readBytes()
        assertTrue(settings.delete())
        assertTrue(settings.mkdir())
        File(settings, "keep").writeText("block")
        assertThrows(Exception::class.java) { store.importImage { ByteArrayInputStream(png()) } }
        assertTrue(File(root, requireNotNull(selection.fileName)).exists())
        assertTrue(File(settings, "keep").delete())
        assertTrue(settings.delete())
        settings.writeBytes(saved)
        assertEquals(selection, store.read())
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
