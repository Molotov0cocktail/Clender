package com.molotov.clender.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.Files

class FileDiagnosticStore(
    private val directory: File,
    private val maxFileBytes: Int = DEFAULT_FILE_BYTES
) : DiagnosticLogger {
    private val lock = Any()

    init {
        require(maxFileBytes in MINIMUM_FILE_BYTES..DEFAULT_FILE_BYTES)
    }

    override fun record(record: DiagnosticRecord) {
        runCatching {
            synchronized(lock) {
                ensureDirectory()
                val current = checkedFile("current.log")
                val previous = checkedFile("previous.log")
                val bytes = DiagnosticCodec.encode(System.currentTimeMillis(), record)
                    .toByteArray(Charsets.UTF_8)
                if (previous.length() > maxFileBytes) deleteChecked(previous)
                if (current.length() > maxFileBytes) deleteChecked(current)
                if (current.length() + bytes.size > maxFileBytes) {
                    deleteChecked(previous)
                    if (!current.renameTo(previous)) throw IOException("Diagnostic rotation failed")
                }
                current.appendBytes(bytes)
            }
        }
    }

    fun snapshot(): ByteArray = runCatching { snapshotChecked() }.getOrDefault(byteArrayOf())

    internal fun snapshotChecked(): ByteArray = synchronized(lock) {
        ensureDirectory()
        buildString {
            for (name in listOf("previous.log", "current.log")) {
                val file = checkedFile(name)
                val bytes = readBounded(file)
                bytes.decodeToString().lineSequence().forEach { line ->
                    DiagnosticCodec.sanitize(line)?.let { append(it) }
                }
            }
        }.toByteArray(Charsets.UTF_8)
    }

    private fun readBounded(file: File): ByteArray {
        if (!file.exists() || file.length() > maxFileBytes) return byteArrayOf()
        // The extra byte detects external growth without unbounded allocation.
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(maxFileBytes + 1)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            buffer.copyOf(size)
        }
        return bytes.takeIf { it.size <= maxFileBytes } ?: byteArrayOf()
    }

    fun clear(): Boolean = runCatching {
        synchronized(lock) {
            ensureDirectory()
            deleteChecked(checkedFile("current.log"))
            deleteChecked(checkedFile("previous.log"))
        }
    }.isSuccess

    private fun ensureDirectory() {
        if (Files.isSymbolicLink(directory.toPath()) ||
            (!directory.isDirectory && !directory.mkdirs())
        ) {
            throw IOException("Diagnostic directory unavailable")
        }
    }

    private fun checkedFile(name: String): File = File(directory, name).also {
        if (Files.isSymbolicLink(it.toPath()) || (it.exists() && !it.isFile)) {
            throw IOException("Invalid diagnostic file")
        }
    }

    private fun deleteChecked(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Diagnostic deletion failed")
    }

    companion object {
        private const val MINIMUM_FILE_BYTES = 256
        private const val DEFAULT_FILE_BYTES = 131_072
    }
}
