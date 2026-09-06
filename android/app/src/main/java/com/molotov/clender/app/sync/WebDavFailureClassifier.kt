package com.molotov.clender.app.sync

import com.molotov.clender.data.network.webdav.WebDavTransportException
import com.molotov.clender.domain.sync.SyncCancellationException
import com.molotov.clender.domain.sync.SyncFailureException
import com.molotov.clender.domain.sync.SyncFailureKind
import com.molotov.clender.domain.sync.WebDavConflictException
import com.molotov.clender.domain.sync.WebDavDocumentException
import java.util.concurrent.CancellationException

/** Raised when a WebDAV secret is missing, invalid or unusable. No secrets in fields. */
class WebDavSecretException : RuntimeException()

/** Raised when captured WebDAV settings are invalid or the binding is stale. */
class WebDavSettingsException : RuntimeException()

/**
 * Maps failure [Throwable]s to the bounded [SyncFailureCode] set without ever
 * reading or exposing messages, bodies or network credentials.
 */
class WebDavFailureClassifier {
    fun classify(error: Throwable): SyncFailureCode = when (error) {
        is WebDavSecretException -> SyncFailureCode.SECRET
        is WebDavSettingsException -> SyncFailureCode.SETTINGS
        is WebDavConflictException -> SyncFailureCode.CONFLICT
        is WebDavDocumentException -> SyncFailureCode.DOCUMENT
        is SyncCancellationException -> SyncFailureCode.CANCELLED
        is CancellationException -> SyncFailureCode.CANCELLED
        is WebDavTransportException -> transportCode(error)
        is SyncFailureException -> failureKindCode(error)
        else -> SyncFailureCode.INTERNAL
    }

    private fun transportCode(error: WebDavTransportException): SyncFailureCode =
        if (error.statusCode in AUTH_CODES) {
            SyncFailureCode.AUTH
        } else {
            SyncFailureCode.TRANSPORT
        }

    private fun failureKindCode(error: SyncFailureException): SyncFailureCode = when (error.kind) {
        SyncFailureKind.CONFLICT -> SyncFailureCode.CONFLICT
        SyncFailureKind.DOCUMENT -> SyncFailureCode.DOCUMENT
        SyncFailureKind.TRANSPORT -> SyncFailureCode.TRANSPORT
        SyncFailureKind.UNKNOWN -> SyncFailureCode.INTERNAL
    }

    private companion object {
        val AUTH_CODES = setOf(401, 403)
    }
}
