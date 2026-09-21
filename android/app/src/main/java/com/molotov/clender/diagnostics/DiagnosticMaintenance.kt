package com.molotov.clender.diagnostics

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class MaintenanceResult { SUCCESS, FAILURE }

interface DiagnosticMaintenance {
    suspend fun exportTo(output: OutputStream): MaintenanceResult

    suspend fun clearLogs(): MaintenanceResult

    suspend fun clearTemporaryCache(): MaintenanceResult
}

class FileDiagnosticMaintenance(
    private val store: FileDiagnosticStore,
    private val exportCacheDirectory: File,
    private val appVersionCode: Long = 0,
    private val sdkInt: Int = 0
) : DiagnosticMaintenance {
    private val mutex = Mutex()

    override suspend fun exportTo(output: OutputStream): MaintenanceResult = operate {
        val temporary = exportFile()
        val exported = runCatching {
            val bytes = exportHeader().toByteArray(Charsets.UTF_8) + store.snapshotChecked()
            temporary.writeBytes(bytes)
            currentCoroutineContext().ensureActive()
            output.write(bytes)
            output.flush()
        }
        val cleaned = runCatching { deleteTemporary(temporary) }
        exported.exceptionOrNull()?.let { failure ->
            cleaned.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        cleaned.getOrThrow()
    }

    override suspend fun clearLogs(): MaintenanceResult = operate {
        if (!store.clear()) throw IOException("Diagnostic deletion failed")
        deleteTemporary(exportFile())
    }

    override suspend fun clearTemporaryCache(): MaintenanceResult = operate {
        deleteTemporary(exportFile())
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun operate(action: suspend () -> Unit): MaintenanceResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    currentCoroutineContext().ensureActive()
                    action()
                    MaintenanceResult.SUCCESS
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    MaintenanceResult.FAILURE
                }
            }
        }

    private fun deleteTemporary(temporary: File) {
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("Diagnostic cleanup failed")
        }
    }

    private fun exportFile(): File {
        if (Files.isSymbolicLink(exportCacheDirectory.toPath()) ||
            (!exportCacheDirectory.isDirectory && !exportCacheDirectory.mkdirs())
        ) {
            throw IOException("Diagnostic cache unavailable")
        }
        return File(exportCacheDirectory, "export.tmp").also {
            if (Files.isSymbolicLink(it.toPath()) || (it.exists() && !it.isFile)) {
                throw IOException("Invalid diagnostic cache file")
            }
        }
    }

    private fun exportHeader(): String = "clender-diagnostics-schema=1\n" +
        "appVersionCode=${appVersionCode.takeIf { it in 0..Int.MAX_VALUE.toLong() } ?: 0}\n" +
        "sdkInt=${sdkInt.takeIf { it in 0..MAX_SDK } ?: 0}\n" +
        "timestampMs|event|phase|result|elapsedMs|httpStatus|" +
        "inputTokens|outputTokens|contextTokens|" +
        "failure|crashSite|crashLine\n"

    companion object {
        private const val MAX_SDK = 1_000
    }
}
