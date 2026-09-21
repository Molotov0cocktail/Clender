package com.molotov.clender.diagnostics

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDiagnosticStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun storesTypedRecordsAndRejectsCorruptOrInjectedLines() {
        val directory = temporary.newFolder()
        val store = FileDiagnosticStore(directory)
        store.record(DiagnosticRecord(DiagnosticEvent.AI_REQUEST, DiagnosticPhase.CONNECT))
        File(directory, "current.log").appendText(
            "secret-marker\n0|AI_REQUEST|READ|SUCCESS|secret-marker|-|-|-|-\n"
        )
        val text = store.snapshot().decodeToString()
        assertTrue(text.contains("AI_REQUEST|CONNECT"))
        assertFalse(text.contains("secret-marker"))
    }

    @Test
    fun rotatesWithinFixedDiskAndExportLimits() {
        val directory = temporary.newFolder()
        val store = FileDiagnosticStore(directory, maxFileBytes = 512)
        repeat(500) {
            store.record(DiagnosticRecord(DiagnosticEvent.AI_REQUEST, elapsedMs = it.toLong()))
        }
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 1_024)
        assertTrue(store.snapshot().size <= 1_024)
        assertTrue(store.snapshot().decodeToString().contains("|499|"))
    }

    @Test
    fun invalidNumericValuesNeverEnterTheLog() {
        val store = FileDiagnosticStore(temporary.newFolder())
        store.record(
            DiagnosticRecord(
                DiagnosticEvent.AI_BUDGET,
                elapsedMs = -1,
                httpStatus = 700,
                inputTokens = Long.MAX_VALUE,
                outputTokens = -1
            )
        )
        val text = store.snapshot().decodeToString()
        assertTrue(text.isNotEmpty())
        assertFalse(text.contains(Long.MAX_VALUE.toString()))
        assertFalse(text.contains("|700|"))
        assertFalse(text.contains("|-1|"))
    }

    @Test
    fun storageFailureNeverEscapesRecord() {
        val store = FileDiagnosticStore(temporary.newFile())
        store.record(DiagnosticRecord(DiagnosticEvent.APP_START))
        assertTrue(store.snapshot().isEmpty())
        assertFalse(store.clear())
    }

    @Test
    fun concurrentRecordSnapshotAndClearRemainBounded() {
        val directory = temporary.newFolder()
        val store = FileDiagnosticStore(directory, maxFileBytes = 512)
        val executor = Executors.newFixedThreadPool(3)
        val futures = (0..2).map { worker ->
            executor.submit {
                repeat(100) {
                    when (worker) {
                        0 -> store.record(DiagnosticRecord(DiagnosticEvent.SYNC))
                        1 -> store.snapshot()
                        else -> store.clear()
                    }
                }
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        futures.forEach { it.get() }
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 1_024)
    }

    @Test
    fun exportAndCleanupPreserveUserDataAndReportWriteFailure() = runBlocking {
        val root = temporary.newFolder()
        val logs = FileDiagnosticStore(File(root, "diagnostics"))
        val cache = File(root, "diagnostic-exports").apply { mkdirs() }
        val protected = listOf(
            "events.db",
            "settings",
            "background",
            "alarm-sound",
            "conversations"
        )
            .map { File(root, it).apply { writeText("synthetic-user-data") } }
        val unrelatedCache = File(cache, "not-ours").apply { writeText("keep") }
        File(cache, "export.tmp").writeText("abandoned")
        val maintenance = FileDiagnosticMaintenance(logs, cache, appVersionCode = 3, sdkInt = 36)
        assertEquals(MaintenanceResult.SUCCESS, maintenance.clearTemporaryCache())
        assertFalse(File(cache, "export.tmp").exists())
        assertTrue(unrelatedCache.exists())
        logs.record(DiagnosticRecord(DiagnosticEvent.APP_START))
        val output = ByteArrayOutputStream()
        assertEquals(MaintenanceResult.SUCCESS, maintenance.exportTo(output))
        assertTrue(output.toString("UTF-8").contains("APP_START"))
        assertTrue(
            output.toString("UTF-8").startsWith(
                "clender-diagnostics-schema=1\nappVersionCode=3\nsdkInt=36\n"
            )
        )
        assertFalse(File(cache, "export.tmp").exists())
        val broken = object : OutputStream() {
            override fun write(value: Int): Unit = throw IOException("synthetic secret")
        }
        assertEquals(MaintenanceResult.FAILURE, maintenance.exportTo(broken))
        assertEquals(MaintenanceResult.SUCCESS, maintenance.clearLogs())
        assertTrue(logs.snapshot().isEmpty())
        protected.forEach { assertEquals("synthetic-user-data", it.readText()) }
    }

    @Test
    fun crashHandlerOmitsThrowableDetailsAndAlwaysDelegates() {
        val store = FileDiagnosticStore(temporary.newFolder())
        var delegated = false
        val handler = DiagnosticCrashHandler(store) { _, _ -> delegated = true }
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("synthetic secret"))
        assertTrue(delegated)
        val text = store.snapshot().decodeToString()
        assertTrue(text.contains("APP_CRASH"))
        assertTrue(text.contains("STATE_ERROR"))
        assertFalse(text.contains("synthetic secret"))
        var failedLoggerDelegated = false
        DiagnosticCrashHandler(DiagnosticLogger { throw IllegalStateException() }) { _, _ ->
            failedLoggerDelegated = true
        }.uncaughtException(Thread.currentThread(), RuntimeException())
        assertTrue(failedLoggerDelegated)
    }

    @Test
    fun crashSitesAreStableNumericFingerprintsWithoutNamesPathsOrMessages() {
        val records = mutableListOf<DiagnosticRecord>()
        val handler = DiagnosticCrashHandler(DiagnosticLogger { records += it }) { _, _ -> }
        fun crash(method: String, line: Int) {
            val failure = IllegalStateException("synthetic-message-marker")
            failure.stackTrace = arrayOf(
                StackTraceElement("framework.Secret", "secretMethod", "secret/path", 20),
                StackTraceElement("com.molotov.clender.app.TestClass", method, "secret/path", line)
            )
            handler.uncaughtException(Thread.currentThread(), failure)
        }
        crash("runTask", 40)
        crash("runTask", 41)
        crash("anotherTask", 40)
        assertEquals(records[0].crashSite, records[1].crashSite)
        assertTrue(records[0].crashSite != records[2].crashSite)
        assertEquals(40, records[0].crashLine)
        val store = FileDiagnosticStore(temporary.newFolder())
        records.forEach(store::record)
        val text = store.snapshot().decodeToString()
        listOf(
            "synthetic-message-marker",
            "secret/path",
            "TestClass",
            "runTask",
            "framework"
        ).forEach {
            assertFalse(text.contains(it))
        }
    }

    @Test
    fun obfuscatedReleaseFrameRetainsAStableLocationFingerprint() {
        val records = mutableListOf<DiagnosticRecord>()
        val handler = DiagnosticCrashHandler(DiagnosticLogger { records += it }) { _, _ -> }
        repeat(2) {
            val failure = IllegalStateException("synthetic-message-marker")
            failure.stackTrace = arrayOf(
                StackTraceElement("java.lang.Thread", "run", "Thread.java", 100),
                StackTraceElement("x.y.a", "b", "synthetic-secret-file", 42)
            )
            handler.uncaughtException(Thread.currentThread(), failure)
        }
        assertTrue(records[0].crashSite != null)
        assertEquals(records[0].crashSite, records[1].crashSite)
        assertEquals(42, records[0].crashLine)
    }

    @Test
    fun concurrentCacheCleanupWaitsUntilExportHasCompleted() = runBlocking {
        val store = FileDiagnosticStore(temporary.newFolder())
        val cache = temporary.newFolder()
        val maintenance = FileDiagnosticMaintenance(store, cache)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val output = object : OutputStream() {
            override fun write(value: Int) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val export = executor.submit<MaintenanceResult> {
                runBlocking { maintenance.exportTo(output) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val clean = executor.submit<MaintenanceResult> {
                runBlocking { maintenance.clearTemporaryCache() }
            }
            assertTrue(File(cache, "export.tmp").isFile)
            assertFalse(clean.isDone)
            release.countDown()
            assertEquals(MaintenanceResult.SUCCESS, export.get(5, TimeUnit.SECONDS))
            assertEquals(MaintenanceResult.SUCCESS, clean.get(5, TimeUnit.SECONDS))
            assertFalse(File(cache, "export.tmp").exists())
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
