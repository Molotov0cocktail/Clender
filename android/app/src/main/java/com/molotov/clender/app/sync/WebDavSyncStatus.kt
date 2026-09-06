package com.molotov.clender.app.sync

/** Read-only WebDAV availability surface fed by non-secret settings flows. */
data class WebDavAvailability(val enabled: Boolean, val passwordConfigured: Boolean)

/**
 * In-memory synchronization state. Never carries URLs, usernames, passwords,
 * authorization headers, exception bodies or complete remote documents.
 */
sealed interface SyncState {
    data object Disabled : SyncState
    data object Unconfigured : SyncState
    data object Idle : SyncState
    data class Running(val pending: Boolean) : SyncState
    data class Success(val uploaded: Boolean, val localChanged: Boolean, val eventCount: Int) :
        SyncState

    data class Failed(val code: SyncFailureCode) : SyncState
}

enum class SyncFailureCode {
    AUTH,
    CONFLICT,
    DOCUMENT,
    TRANSPORT,
    SECRET,
    SETTINGS,
    CANCELLED,
    INTERNAL
}

enum class SyncRequestDecision {
    STARTED,
    COALESCED,
    DISABLED,
    UNCONFIGURED,
    REFRESHING,
    SHUTDOWN
}
