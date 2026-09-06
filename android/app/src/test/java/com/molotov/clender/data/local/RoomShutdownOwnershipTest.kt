package com.molotov.clender.data.local

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDateTime
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = Application::class)
class RoomShutdownOwnershipTest {
    @Test
    fun coldFindReleasesQueryBeforeCloseReturns() = verifyShutdown(warm = false, flow = false)

    @Test
    fun warmFindReleasesQueryBeforeCloseReturns() = verifyShutdown(warm = true, flow = false)

    @Test
    fun coldFlowReleasesQueryBeforeCloseReturns() = verifyShutdown(warm = false, flow = true)

    @Test
    fun warmFlowReleasesQueryBeforeCloseReturns() = verifyShutdown(warm = true, flow = true)

    private fun verifyShutdown(warm: Boolean, flow: Boolean) {
        ShutdownFixture().use { fixture ->
            fixture.startQuery(warm, flow)
            fixture.cancelAndClose()
            fixture.verifyOwnership()
        }
    }
}

private const val WAIT_SECONDS = 10L
private const val WAIT_MILLIS = 10_000L

private class ShutdownFixture : AutoCloseable {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "shutdown-${UUID.randomUUID()}.db"
    private val executor = Executors.newFixedThreadPool(2)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probe = ShutdownProbe()
    private val databaseHolder = lazy {
        Room.databaseBuilder(context, ClenderDatabase::class.java, databaseName)
            .openHelperFactory { configuration ->
                val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
                TracedOpenHelper(helper, probe)
            }
            .setQueryExecutor(executor)
            .setTransactionExecutor(executor)
            .build()
    }
    private val database by databaseHolder
    private var queryJob: Job? = null
    private var closeThread: Thread? = null
    private val closeFailure = AtomicReference<Throwable?>()
    private val delivered = AtomicBoolean(false)
    private val queryCompletion = AtomicReference<Throwable?>()

    fun startQuery(warm: Boolean, flow: Boolean) {
        val repository = RoomEventRepository(database)
        if (warm) runBlocking { withTimeout(WAIT_MILLIS) { repository.findById(7) } }
        probe.armed.set(true)
        queryJob = scope.async {
            if (flow) {
                val start = LocalDateTime.of(2026, 9, 6, 0, 0)
                repository.observeRange(start, start.plusDays(1)).first()
            } else {
                repository.findById(7)
            }
            delivered.set(true)
        }.also { job ->
            job.invokeOnCompletion { cause ->
                queryCompletion.set(cause)
                probe.record("queryCompleted")
            }
        }
        probe.await(probe.queryPaused, "queryPaused")
    }

    fun cancelAndClose() {
        queryJob!!.cancel()
        probe.record("cancelRequested")
        val requested = CountDownLatch(1)
        closeThread = Thread(
            {
                try {
                    probe.record("closeRequested")
                    requested.countDown()
                    database.close()
                    probe.record("closeReturned")
                } catch (failure: Throwable) {
                    closeFailure.set(failure)
                }
            },
            "room-shutdown-diagnostic"
        ).also { it.start() }
        probe.await(requested, "closeRequested")
        probe.record("queryReleased")
        probe.releaseQuery.countDown()
    }

    fun verifyOwnership() {
        joinQueryAndClose()
        closeFailure.get()?.let { throw AssertionError(probe.snapshot(), it) }
        stopExecutor()
        assertTrue(probe.snapshot(), queryJob!!.isCancelled)
        assertTrue(probe.snapshot(), queryCompletion.get() is CancellationException)
        assertFalse(probe.snapshot(), delivered.get())
        probe.before("cursorAcquired", "queryPaused")
        probe.before("queryPaused", "cancelRequested")
        probe.before("cancelRequested", "closeRequested")
        probe.before("queryReleased", "cursorClosed")
        probe.before("cursorClosed", "closeReturned")
        probe.before("helperClosed", "closeReturned")
        assertTrue("Expected a real database; ${probe.snapshot()}", databaseFileExists())
    }

    private fun deleteDatabaseOnce() {
        if (!databaseHolder.isInitialized()) return
        val deleted = context.deleteDatabase(databaseName)
        probe.record("delete=$deleted")
        assertTrue(probe.snapshot(), deleted)
        assertFalse(probe.snapshot(), databaseFileExists())
    }

    private fun databaseFileExists() = context.getDatabasePath(databaseName).exists()

    private fun joinQueryAndClose() {
        runBlocking { withTimeout(WAIT_MILLIS) { queryJob?.join() } }
        closeThread?.join(WAIT_MILLIS)
        assertFalse(
            "Close thread did not finish; ${probe.snapshot()}",
            closeThread?.isAlive == true
        )
    }

    private fun stopExecutor() {
        executor.shutdown()
        if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            assertTrue(probe.snapshot(), executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS))
        }
    }

    override fun close() {
        probe.releaseQuery.countDown()
        scope.cancel()
        val failures = listOf<() -> Unit>(
            { joinQueryAndClose() },
            { if (databaseHolder.isInitialized()) database.close() },
            { stopExecutor() },
            { deleteDatabaseOnce() }
        ).mapNotNull { cleanup -> runCatching(cleanup).exceptionOrNull() }
        println("Room shutdown trace: ${probe.snapshot()}")
        if (failures.isNotEmpty()) {
            throw AssertionError("Diagnostic cleanup failed; ${probe.snapshot()}").also { error ->
                failures.forEach(error::addSuppressed)
            }
        }
    }
}

private class ShutdownProbe {
    val armed = AtomicBoolean(false)
    val queryPaused = CountDownLatch(1)
    val releaseQuery = CountDownLatch(1)
    private val events = Collections.synchronizedList(mutableListOf<String>())

    fun record(event: String) {
        events.add(event)
    }

    fun snapshot(): String = synchronized(events) { events.joinToString(" -> ") }

    fun await(latch: CountDownLatch, phase: String) {
        check(latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "$phase timed out; ${snapshot()}" }
    }

    fun before(first: String, second: String) {
        val copy = synchronized(events) { events.toList() }
        val ordered = copy.indexOf(first) >= 0 && copy.indexOf(second) > copy.indexOf(first)
        assertTrue(snapshot(), ordered)
    }

    fun wrap(query: SupportSQLiteQuery, cursor: Cursor): Cursor {
        val isEventRead = query.sql.trimStart().startsWith("SELECT", ignoreCase = true) &&
            query.sql.contains("FROM events", ignoreCase = true)
        if (!isEventRead || !armed.compareAndSet(true, false)) return cursor
        record("cursorAcquired")
        return PausedCursor(cursor, this)
    }
}

private class PausedCursor(cursor: Cursor, private val probe: ShutdownProbe) :
    CursorWrapper(cursor) {
    private var paused = false

    override fun moveToNext(): Boolean {
        val result = super.moveToNext()
        if (!paused) {
            paused = true
            probe.record("queryPaused")
            probe.queryPaused.countDown()
            probe.await(probe.releaseQuery, "queryRelease")
        }
        return result
    }

    override fun close() {
        super.close()
        probe.record("cursorClosed")
    }
}

private class TracedOpenHelper(
    private val delegate: SupportSQLiteOpenHelper,
    private val probe: ShutdownProbe
) : SupportSQLiteOpenHelper by delegate {
    override val writableDatabase: SupportSQLiteDatabase
        get() = TracedDatabase(delegate.writableDatabase, probe)

    override val readableDatabase: SupportSQLiteDatabase
        get() = TracedDatabase(delegate.readableDatabase, probe)

    override fun close() {
        probe.record("helperCloseEntered")
        delegate.close()
        probe.record("helperClosed")
    }
}

private class TracedDatabase(
    private val delegate: SupportSQLiteDatabase,
    private val probe: ShutdownProbe
) : SupportSQLiteDatabase by delegate {
    override fun query(query: SupportSQLiteQuery): Cursor = probe.wrap(query, delegate.query(query))

    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
        probe.wrap(query, delegate.query(query, cancellationSignal))
}
