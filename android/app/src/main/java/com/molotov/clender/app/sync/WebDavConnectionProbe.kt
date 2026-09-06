package com.molotov.clender.app.sync

import com.molotov.clender.data.network.webdav.WebDavClient
import java.util.concurrent.atomic.AtomicBoolean

sealed interface WebDavProbeResult {
    data object Success : WebDavProbeResult
    data object Busy : WebDavProbeResult
    data class Failed(val code: SyncFailureCode) : WebDavProbeResult
}

/**
 * Executes a single PROPFIND Depth: 0 against an HTTPS WebDAV directory.
 * Zero Gate lease -> [WebDavProbeResult.Busy] with no client/session created;
 * the supplied [password] is always zeroed before returning and the session is
 * always closed (the session itself also wipes its password copy).
 */
class WebDavConnectionProbe(
    private val gate: WebDavOperationGate,
    private val clientFactory: (directoryUrl: String, username: String) -> WebDavClient,
    private val cancelAll: () -> Unit,
    private val classifier: WebDavFailureClassifier = WebDavFailureClassifier()
) {
    private val cancelled = AtomicBoolean(false)

    suspend fun probe(
        directoryUrl: String,
        username: String,
        password: CharArray
    ): WebDavProbeResult {
        val lease = gate.tryAcquire()
        if (lease == null) return WebDavProbeResult.Busy
        return try {
            val client = clientFactory(directoryUrl, username)
            val session = client.openSession(password)
            try {
                session.probe()
                WebDavProbeResult.Success
            } finally {
                session.close()
            }
        } catch (ignored: Throwable) {
            probeFailure(ignored)
        } finally {
            lease.close()
            password.fill('\u0000')
        }
    }

    private fun probeFailure(ignored: Throwable): WebDavProbeResult = if (cancelled.get()) {
        WebDavProbeResult.Failed(SyncFailureCode.CANCELLED)
    } else {
        WebDavProbeResult.Failed(classifier.classify(ignored))
    }

    fun cancelInFlight() {
        cancelled.set(true)
        cancelAll()
    }
}
