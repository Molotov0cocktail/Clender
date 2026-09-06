package com.molotov.clender.app.sync

import com.molotov.clender.data.network.webdav.WebDavTransportException
import com.molotov.clender.domain.sync.SyncCancellationException
import com.molotov.clender.domain.sync.SyncFailureException
import com.molotov.clender.domain.sync.SyncFailureKind
import com.molotov.clender.domain.sync.WebDavConflictException
import com.molotov.clender.domain.sync.WebDavDocumentException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T46-C2b contract tests for WebDavOperationGate, SyncState/SyncFailureCode,
 * WebDavAvailability/SyncRequestDecision, WebDavFailureClassifier,
 * WebDavSecretException/WebDavSettingsException and WebDavRunResult.
 *
 * All symbols referenced here live in com.molotov.clender.app.sync (same package)
 * except WebDavSectionSnapshot (com.molotov.clender.data.settings) and the
 * pre-existing domain/network failure types. None of the app.sync symbols exist
 * yet, so this file is the compilation red light for the production contract.
 */
class WebDavSyncContractsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun tryAcquireReturnsExactlyOneLeaseWhileGateIsOpenAndIdle() {
        val gate = WebDavOperationGate()
        val first = gate.tryAcquire()
        assertNotNull(first)
        assertNull(gate.tryAcquire())
        first!!.close()
        assertNotNull(gate.tryAcquire())
    }

    @Test
    fun leaseCloseIsIdempotentAndReleasesExactlyOnce() {
        val gate = WebDavOperationGate()
        val lease = requireNotNull(gate.tryAcquire())
        assertNull(gate.tryAcquire())
        lease.close()
        lease.close()
        assertNotNull(gate.tryAcquire()?.also { it.close() })
    }

    @Test
    fun withExclusiveWaitsForAnyHeldLeaseThenExecutesAndReturnsValue() = runBlocking {
        val gate = WebDavOperationGate()
        val held = requireNotNull(gate.tryAcquire())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = async {
            gate.withExclusive {
                entered.complete(Unit)
                release.await()
                "result"
            }
        }
        assertTrue(runCatching { withTimeout(150) { entered.await() } }.isFailure)
        held.close()
        withTimeout(2_000) { entered.await() }
        release.complete(Unit)
        assertEquals("result", withTimeout(2_000) { job.await() })
    }

    @Test
    fun withExclusiveSerializesConcurrentBlocks() = runBlocking {
        val gate = WebDavOperationGate()
        val firstEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val first = async {
            gate.withExclusive {
                firstEntered.complete(Unit)
                firstRelease.await()
            }
        }
        val second = async {
            gate.withExclusive {
                secondEntered.complete(Unit)
                secondRelease.await()
            }
        }
        withTimeout(2_000) { firstEntered.await() }
        assertTrue(runCatching { withTimeout(150) { secondEntered.await() } }.isFailure)
        firstRelease.complete(Unit)
        withTimeout(2_000) { secondEntered.await() }
        secondRelease.complete(Unit)
        first.await()
        second.await()
    }

    @Test
    fun withExclusiveOnClosedGateThrowsIllegalStateException() = runBlocking<Unit> {
        val gate = WebDavOperationGate()
        gate.close()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { gate.withExclusive<Unit> { } }
        }
    }

    @Test
    fun waitersFailClosedWhenGateClosesWhileWaiting() = runBlocking<Unit> {
        val gate = WebDavOperationGate()
        requireNotNull(gate.tryAcquire())
        val waiter = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            gate.withExclusive<Unit> { error("block must never run on a closed gate") }
        }
        gate.close()
        val thrown = runCatching { withTimeout(5_000) { waiter.await() } }.exceptionOrNull()
        assertTrue(
            "expected IllegalStateException, got ${thrown?.javaClass?.name}",
            thrown is IllegalStateException
        )
        waiter.join()
    }

    @Test
    fun closeIsIdempotentAndInvalidatesAcquireAndWaiterPaths() = runBlocking<Unit> {
        val gate = WebDavOperationGate()
        gate.close()
        gate.close()
        assertNull(gate.tryAcquire())
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                gate.withExclusive<String> { "must never run" }
            }
        }
    }

    @Test
    fun syncStateValuesCarryOnlyBoundedFields() {
        val running = SyncState.Running(pending = true)
        val success = SyncState.Success(uploaded = true, localChanged = false, eventCount = 3)
        val failed = SyncState.Failed(SyncFailureCode.AUTH)
        assertTrue(running.pending)
        assertTrue(success.uploaded)
        assertFalse(success.localChanged)
        assertEquals(3, success.eventCount)
        assertEquals(SyncFailureCode.AUTH, failed.code)
        assertEquals(SyncState.Success(true, false, 3), success)
        assertEquals(SyncState.Failed(SyncFailureCode.AUTH), failed)
        assertEquals(SyncState.Disabled, SyncState.Disabled)
        assertEquals(SyncState.Unconfigured, SyncState.Unconfigured)
        assertEquals(SyncState.Idle, SyncState.Idle)
    }

    @Test
    fun failureAndDecisionEnumsAreLimitedAndStable() {
        assertEquals(
            setOf(
                "AUTH",
                "CONFLICT",
                "DOCUMENT",
                "TRANSPORT",
                "SECRET",
                "SETTINGS",
                "CANCELLED",
                "INTERNAL"
            ),
            SyncFailureCode.entries.mapTo(mutableSetOf()) { it.name }
        )
        assertEquals(
            setOf("STARTED", "COALESCED", "DISABLED", "UNCONFIGURED", "REFRESHING", "SHUTDOWN"),
            SyncRequestDecision.entries.mapTo(mutableSetOf()) { it.name }
        )
    }

    @Test
    fun availabilityExposesOnlyEnabledAndPasswordConfiguredFlags() {
        val configured = WebDavAvailability(enabled = true, passwordConfigured = true)
        val blank = WebDavAvailability(enabled = false, passwordConfigured = false)
        assertEquals(WebDavAvailability(true, true), configured)
        assertEquals(WebDavAvailability(false, false), blank)
        assertTrue(configured.enabled)
        assertTrue(configured.passwordConfigured)
        assertFalse(blank.enabled)
        assertFalse(blank.passwordConfigured)
    }

    @Test
    fun syncStateAndDecisionToStringsNeverExposeSecrets() {
        val password = "webdav-password-7f3d9c2a"
        val url = "https://alice:$password@example.invalid/dir/"
        val username = "alice@example.invalid"
        val secrets = listOf(password, url, username)
        val states: List<SyncState> = listOf(
            SyncState.Disabled,
            SyncState.Unconfigured,
            SyncState.Idle,
            SyncState.Running(pending = false),
            SyncState.Running(pending = true),
            SyncState.Success(uploaded = true, localChanged = false, eventCount = 12),
            SyncState.Failed(SyncFailureCode.SECRET)
        )
        states.forEach { state ->
            val rendered = state.toString()
            secrets.forEach { secret ->
                assertFalse("$secret leaked in $rendered", secret in rendered)
            }
        }
        val decisions = SyncRequestDecision.entries.map { it.toString() }
        decisions.forEach { rendered ->
            secrets.forEach { secret ->
                assertFalse("$secret leaked in $rendered", secret in rendered)
            }
        }
        val availability = WebDavAvailability(enabled = true, passwordConfigured = true).toString()
        secrets.forEach { secret ->
            assertFalse("$secret leaked in $availability", secret in availability)
        }
    }

    @Test
    fun runResultsCarryOnlySafeFieldsAndSingleUnconfigured() {
        val completed = WebDavRunResult.Completed(
            uploaded = true,
            localChanged = false,
            eventCount = 5
        )
        assertEquals(WebDavRunResult.Completed(true, false, 5), completed)
        assertTrue(completed.uploaded)
        assertFalse(completed.localChanged)
        assertEquals(5, completed.eventCount)
        assertEquals(WebDavRunResult.Unconfigured, WebDavRunResult.Unconfigured)
        val rendered = listOf(completed.toString(), WebDavRunResult.Unconfigured.toString())
        rendered.forEach { text ->
            assertFalse("https://" in text)
            assertFalse("password" in text)
        }
    }

    @Test
    fun secretAndSettingsExceptionsRequireNoSensitiveArguments() {
        val secret = WebDavSecretException()
        val settings = WebDavSettingsException()
        assertTrue(secret is RuntimeException)
        assertTrue(settings is RuntimeException)
        val rendered = listOf(secret.toString(), settings.toString())
        rendered.forEach { text ->
            assertFalse("password" in text)
            assertFalse("https://" in text)
            assertFalse("username" in text)
        }
    }

    @Test
    fun classifierMapsBoundedFailuresToTheirCodes() {
        val classifier = WebDavFailureClassifier()
        listOf(
            WebDavSecretException() to SyncFailureCode.SECRET,
            WebDavSettingsException() to SyncFailureCode.SETTINGS,
            WebDavConflictException("conflict") to SyncFailureCode.CONFLICT,
            SyncFailureException(
                remoteVisibleChanged = true,
                kind = SyncFailureKind.CONFLICT,
                httpStatusCode = 412
            ) to SyncFailureCode.CONFLICT,
            WebDavDocumentException("document") to SyncFailureCode.DOCUMENT,
            SyncFailureException(
                remoteVisibleChanged = false,
                kind = SyncFailureKind.DOCUMENT,
                httpStatusCode = null
            ) to SyncFailureCode.DOCUMENT,
            WebDavTransportException("transport") to SyncFailureCode.TRANSPORT,
            WebDavTransportException("not found", statusCode = 404) to SyncFailureCode.TRANSPORT,
            SyncFailureException(
                remoteVisibleChanged = false,
                kind = SyncFailureKind.TRANSPORT,
                httpStatusCode = 503
            ) to SyncFailureCode.TRANSPORT,
            SyncFailureException(
                remoteVisibleChanged = true,
                kind = SyncFailureKind.TRANSPORT,
                httpStatusCode = 401
            ) to SyncFailureCode.TRANSPORT,
            WebDavTransportException("unauthorized", statusCode = 401) to SyncFailureCode.AUTH,
            WebDavTransportException("forbidden", statusCode = 403) to SyncFailureCode.AUTH,
            SyncCancellationException(remoteVisibleChanged = false) to SyncFailureCode.CANCELLED,
            CancellationException("cancelled") to SyncFailureCode.CANCELLED,
            SyncFailureException(
                remoteVisibleChanged = false,
                kind = SyncFailureKind.UNKNOWN,
                httpStatusCode = null
            ) to SyncFailureCode.INTERNAL,
            IllegalArgumentException("unexpected") to SyncFailureCode.INTERNAL,
            RuntimeException("unexpected") to SyncFailureCode.INTERNAL
        ).forEach { (error, expected) ->
            assertEquals("${error::class.qualifiedName}", expected, classifier.classify(error))
        }
    }

    @Test
    fun classifierFallsBackToInternalAndHandlesPlainCancellationShapes() {
        val classifier = WebDavFailureClassifier()
        assertEquals(
            SyncFailureCode.CANCELLED,
            classifier.classify(CancellationException(null))
        )
        assertEquals(SyncFailureCode.INTERNAL, classifier.classify(Throwable()))
        assertEquals(
            SyncFailureCode.INTERNAL,
            classifier.classify(IllegalStateException("no message"))
        )
    }
}
