package com.molotov.clender.data.settings

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** IO-worker-only import and removal; the selected file is local to this installation. */
class AlarmSoundStore(
    private val directory: File,
    private val validate: (File) -> Unit = ::validateAlarmSound
) {
    private val selected get() = File(directory, "selected.audio")
    private val state = states.computeIfAbsent(directory.absoluteFile.normalize().path) {
        SelectionState()
    }

    fun selectedFile(): File? = selected.takeIf { it.isFile && it.length() in 1..MAX_INPUT_BYTES }

    fun importSound(open: () -> InputStream?, checkActive: () -> Unit = {}): File {
        val generation = state.lock.withLock {
            checkActive()
            ++state.generation
        }
        check(directory.mkdirs() || directory.isDirectory) { "Alarm sound directory unavailable" }
        val pending = File.createTempFile("pending-", ".audio", directory)
        try {
            copySound(open, pending, checkActive)
            checkActive()
            validate(pending)
            state.lock.withLock {
                checkActive()
                if (generation != state.generation) {
                    throw CancellationException("Alarm sound selection superseded")
                }
                Files.move(
                    pending.toPath(),
                    selected.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            return selected
        } finally {
            pending.delete()
        }
    }

    fun remove(checkActive: () -> Unit = {}) {
        state.lock.withLock {
            checkActive()
            ++state.generation
            Files.deleteIfExists(selected.toPath())
        }
    }

    private fun copySound(open: () -> InputStream?, pending: File, checkActive: () -> Unit) {
        checkActive()
        requireNotNull(open()) { "Audio unavailable" }.use { input ->
            pending.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                checkActive()
                var count = input.read(buffer)
                while (count != -1) {
                    checkActive()
                    require(count > 0) { "Audio source made no progress" }
                    total += count
                    require(total <= MAX_INPUT_BYTES) { "Audio too large" }
                    output.write(buffer, 0, count)
                    checkActive()
                    count = input.read(buffer)
                }
                require(total > 0) { "Audio is empty" }
                checkActive()
                output.fd.sync()
            }
        }
    }

    private class SelectionState {
        val lock = ReentrantLock()
        var generation = 0L
    }

    private companion object {
        const val MAX_INPUT_BYTES = 32L * 1024 * 1024
        val states = ConcurrentHashMap<String, SelectionState>()
    }
}

internal fun validateAlarmSound(file: File) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        require(
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
        ) { "No audio track" }
    } finally {
        extractor.release()
    }
    val player = MediaPlayer()
    try {
        player.setDataSource(file.absolutePath)
        player.prepare()
        require(player.duration > 0) { "Audio has no playable duration" }
    } finally {
        player.release()
    }
}
