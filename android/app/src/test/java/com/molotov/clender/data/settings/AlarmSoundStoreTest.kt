package com.molotov.clender.data.settings

import android.media.MediaFormat
import android.media.MediaPlayer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AlarmSoundStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun importsReplacesRestoresAndRemovesOnlyItsSelection() {
        val root = temporary.newFolder()
        val unrelated = File(root, "keep.txt").apply { writeText("keep") }
        val validated = mutableListOf<String>()
        val store = AlarmSoundStore(root) { validated += it.readText() }
        assertNull(store.selectedFile())
        val selected = store.importSound({ "first".byteInputStream() })
        assertEquals(File(root, "selected.audio"), selected)
        assertEquals("first", AlarmSoundStore(root).selectedFile()?.readText())
        store.importSound({ "second".byteInputStream() })
        assertEquals(listOf("first", "second"), validated)
        assertEquals("second", selected.readText())
        store.remove()
        store.remove()
        assertNull(store.selectedFile())
        assertEquals(listOf(unrelated.name), root.list()!!.toList())
    }

    @Test
    fun exactLimitAcceptedButEmptyAndOversizePreservePrevious() {
        val root = temporary.newFolder()
        val store = AlarmSoundStore(root) {}
        val selected = store.importSound({ sizedStream(LIMIT) })
        assertEquals(LIMIT.toLong(), selected.length())
        for (size in listOf(0, LIMIT + 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                store.importSound({ sizedStream(size) })
            }
            assertEquals(LIMIT.toLong(), selected.length())
            assertEquals(listOf("selected.audio"), root.list()!!.toList())
        }
    }

    @Test
    fun unavailableDeniedBrokenAndInvalidSourcesKeepOldFileAndCloseStream() {
        val root = temporary.newFolder()
        val original = File(root, "selected.audio").apply { writeText("old") }
        var closed = false
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("synthetic truncated input")
            override fun close() {
                closed = true
            }
        }
        val store = AlarmSoundStore(root) { throw IllegalArgumentException("not playable") }
        assertThrows(IllegalArgumentException::class.java) { store.importSound({ null }) }
        assertThrows(SecurityException::class.java) {
            store.importSound({ throw SecurityException("synthetic denial") })
        }
        assertThrows(IOException::class.java) { store.importSound({ broken }) }
        assertTrue(closed)
        assertThrows(IllegalArgumentException::class.java) {
            store.importSound({ "fake.mp3".byteInputStream() })
        }
        assertEquals("old", original.readText())
        assertEquals(listOf(original.name), root.list()!!.toList())
    }

    @Test
    fun cancellationDuringReadAndAfterValidationCannotCommit() {
        val root = temporary.newFolder()
        val original = File(root, "selected.audio").apply { writeText("old") }
        var active = true
        val checkActive = { if (!active) throw CancellationException("synthetic cancel") }
        val stream = object : ByteArrayInputStream(ByteArray(16384)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                active = false
                return super.read(bytes, offset, length)
            }
        }
        assertThrows(CancellationException::class.java) {
            AlarmSoundStore(root) {}.importSound({ stream }, checkActive)
        }
        active = true
        assertThrows(CancellationException::class.java) {
            AlarmSoundStore(root) { active = false }.importSound(
                { "new".byteInputStream() },
                checkActive
            )
        }
        assertThrows(CancellationException::class.java) {
            AlarmSoundStore(root).remove(checkActive)
        }
        assertEquals("old", original.readText())
        assertEquals(listOf(original.name), root.list()!!.toList())
    }

    @Test
    fun invalidStoredFileAndFailedReplacementDoNotExposePending() {
        val root = temporary.newFolder()
        val selected = File(root, "selected.audio")
        val store = AlarmSoundStore(root) {}
        selected.writeBytes(byteArrayOf())
        assertNull(store.selectedFile())
        RandomAccessFile(selected, "rw").use { it.setLength(LIMIT.toLong() + 1) }
        assertNull(store.selectedFile())
        assertTrue(selected.delete())
        assertTrue(selected.mkdir())
        File(selected, "keep").writeText("keep")
        assertNull(store.selectedFile())
        assertThrows(IOException::class.java) { store.importSound({ "new".byteInputStream() }) }
        assertEquals("keep", File(selected, "keep").readText())
        assertEquals(listOf(selected.name), root.list()!!.toList())
    }

    @Test
    fun cancelledOldInstanceCannotOverwriteNewSelection() {
        val root = temporary.newFolder()
        File(root, "selected.audio").writeText("old")
        val validating = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val active = AtomicBoolean(true)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Boolean> {
                try {
                    AlarmSoundStore(root) {
                        validating.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                    }.importSound({ "obsolete".byteInputStream() }) {
                        if (!active.get()) throw CancellationException()
                    }
                    false
                } catch (_: CancellationException) {
                    true
                }
            }
            assertTrue(validating.await(5, TimeUnit.SECONDS))
            assertEquals("old", AlarmSoundStore(root).selectedFile()?.readText())
            active.set(false)
            val second = executor.submit<File> {
                AlarmSoundStore(root) {}.importSound({ "latest".byteInputStream() })
            }
            assertEquals("latest", second.get(5, TimeUnit.SECONDS).readText())
            resume.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
            assertEquals("latest", AlarmSoundStore(root).selectedFile()?.readText())
            assertEquals(listOf("selected.audio"), root.list()!!.toList())
        } finally {
            resume.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun restoreDoesNotWaitForOldValidationAndPreventsLateCommitWithoutCallback() {
        val root = temporary.newFolder()
        File(root, "selected.audio").writeText("old")
        val validating = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val old = executor.submit<Boolean> {
                try {
                    AlarmSoundStore(root) {
                        validating.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                    }.importSound({ "obsolete".byteInputStream() })
                    false
                } catch (_: CancellationException) {
                    true
                }
            }
            assertTrue(validating.await(5, TimeUnit.SECONDS))
            executor.submit { AlarmSoundStore(root).remove() }.get(5, TimeUnit.SECONDS)
            assertNull(AlarmSoundStore(root).selectedFile())
            resume.countDown()
            assertTrue(old.get(5, TimeUnit.SECONDS))
            assertNull(AlarmSoundStore(root).selectedFile())
            assertTrue(root.list()!!.isEmpty())
        } finally {
            resume.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun platformValidatorRequiresAudioAndPreparesWithoutPlayingThenReleases() {
        val file = temporary.newFile("arbitrary.extension").apply {
            writeText("synthetic shadow media")
        }
        val source = DataSource.toDataSource(file.absolutePath)
        ShadowMediaExtractor.addTrack(
            source,
            MediaFormat.createAudioFormat("audio/raw", 22050, 1),
            byteArrayOf(1)
        )
        ShadowMediaPlayer.addMediaInfo(source, ShadowMediaPlayer.MediaInfo(1000, 0))
        var player: MediaPlayer? = null
        ShadowMediaPlayer.setCreateListener { created, _ -> player = created }
        validateAlarmSound(file)
        val shadow = shadowOf(requireNotNull(player))
        assertEquals(ShadowMediaPlayer.State.END, shadow.state)
        assertFalse(shadow.isReallyPlaying)
    }

    @Test
    fun platformValidatorRejectsEmptyAudioContainerAfterPreparationAndReleases() {
        val file = temporary.newFile().apply { writeText("synthetic empty WAV header") }
        val source = DataSource.toDataSource(file.absolutePath)
        ShadowMediaExtractor.addTrack(
            source,
            MediaFormat.createAudioFormat("audio/raw", 22050, 1),
            byteArrayOf()
        )
        ShadowMediaPlayer.addMediaInfo(source, ShadowMediaPlayer.MediaInfo(0, 0))
        var player: MediaPlayer? = null
        ShadowMediaPlayer.setCreateListener { created, _ -> player = created }
        assertThrows(IllegalArgumentException::class.java) { validateAlarmSound(file) }
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(requireNotNull(player)).state)
    }

    @Test
    fun platformValidatorRejectsVideoOnlyAndUnpreparableAudio() {
        val file = temporary.newFile().apply { writeText("synthetic shadow media") }
        val source = DataSource.toDataSource(file.absolutePath)
        ShadowMediaExtractor.addTrack(
            source,
            MediaFormat.createVideoFormat("video/avc", 16, 16),
            byteArrayOf(1)
        )
        assertThrows(IllegalArgumentException::class.java) { validateAlarmSound(file) }
        ShadowMediaExtractor.addTrack(
            source,
            MediaFormat.createAudioFormat("audio/raw", 22050, 1),
            byteArrayOf(1)
        )
        ShadowMediaPlayer.addException(source, IOException("synthetic unsupported codec"))
        var player: MediaPlayer? = null
        ShadowMediaPlayer.setCreateListener { created, _ -> player = created }
        assertThrows(IOException::class.java) { validateAlarmSound(file) }
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(requireNotNull(player)).state)
    }

    private fun sizedStream(size: Int): InputStream = object : InputStream() {
        private var remaining = size
        override fun read(): Int = if (remaining-- > 0) 1 else -1
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = minOf(length, remaining)
            bytes.fill(1, offset, offset + count)
            remaining -= count
            return count
        }
    }

    private companion object {
        const val LIMIT = 32 * 1024 * 1024
    }
}
