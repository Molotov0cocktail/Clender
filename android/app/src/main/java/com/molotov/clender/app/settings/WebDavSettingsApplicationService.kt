package com.molotov.clender.app.settings

import com.molotov.clender.app.sync.SyncFailureCode
import com.molotov.clender.app.sync.WebDavConnectionProbe
import com.molotov.clender.app.sync.WebDavProbeResult
import com.molotov.clender.data.settings.WebDavPasswordMutation
import com.molotov.clender.data.settings.WebDavPreferences
import com.molotov.clender.data.settings.WebDavSectionSnapshot
import com.molotov.clender.data.settings.validateWebDavDraftOrNull
import java.util.concurrent.CancellationException

enum class WebDavSaveDecision {
    SUCCESS,
    VALIDATION_FAILED,
    SAVE_FAILED,
    INTERNAL
}

enum class WebDavConnectionDecision {
    SUCCESS,
    BUSY,
    UNCONFIGURED,
    VALIDATION_FAILED,
    AUTH,
    DOCUMENT,
    TRANSPORT,
    SECRET,
    CANCELLED,
    INTERNAL
}

enum class WebDavSyncNowDecision {
    SYNC_STARTED,
    SYNC_COALESCED,
    DISABLED,
    UNCONFIGURED,
    VALIDATION_FAILED,
    SAVE_FAILED,
    INTERNAL
}

sealed interface WebDavStoredPassword {
    data class Granted(val value: CharArray) : WebDavStoredPassword
    data object Missing : WebDavStoredPassword
    data object Invalid : WebDavStoredPassword
}

interface WebDavSettingsAtomicPort {
    suspend fun updateWebDav(
        settings: WebDavPreferences,
        mutation: WebDavPasswordMutation
    ): WebDavSectionSnapshot

    /** Reference-identity-bound password read; stale or missing snapshots return null. */
    suspend fun readPassword(snapshot: WebDavSectionSnapshot): CharArray?

    suspend fun readPersistedPassword(): WebDavStoredPassword
}

/**
 * Application-level WebDAV settings operations: atomic save, zero-save probe
 * and save-before-sync. Never logs, keeps or exposes passwords, URLs or
 * usernames in decisions; every CharArray received or produced is zeroed on
 * success, failure, rejection and cancellation paths.
 */
class WebDavSettingsApplicationService(
    private val port: WebDavSettingsAtomicPort,
    private val probe: WebDavConnectionProbe,
    private val requestSyncNow: suspend (WebDavSectionSnapshot) -> WebDavSyncNowDecision,
    private val persistedPasswordConfigured: suspend () -> Boolean
) {
    suspend fun save(
        settings: WebDavPreferences,
        mutation: WebDavPasswordMutation
    ): WebDavSaveDecision {
        if (!validForSave(settings, mutation)) {
            wipe(mutation)
            return WebDavSaveDecision.VALIDATION_FAILED
        }
        return try {
            port.updateWebDav(settings, mutation)
            WebDavSaveDecision.SUCCESS
        } catch (_: IllegalArgumentException) {
            WebDavSaveDecision.VALIDATION_FAILED
        } catch (failure: CancellationException) {
            throw failure
        } catch (ignored: RuntimeException) {
            WebDavSaveDecision.SAVE_FAILED
        } finally {
            wipe(mutation)
        }
    }

    suspend fun testConnection(
        settings: WebDavPreferences,
        draftPassword: CharArray?,
        removePasswordPending: Boolean
    ): WebDavConnectionDecision {
        val preflightFailed = preflightTestDecision(settings, removePasswordPending) != null
        val supplied = if (preflightFailed) null else draftPassword?.takeIf(CharArray::isNotEmpty)
        val stored = if (preflightFailed || supplied != null || removePasswordPending) {
            null
        } else {
            port.readPersistedPassword()
        }
        val password: CharArray? = when {
            preflightFailed -> null
            supplied != null -> supplied
            removePasswordPending -> null
            stored is WebDavStoredPassword.Granted -> stored.value
            else -> null
        }
        return when {
            preflightFailed -> {
                wipe(draftPassword)
                WebDavConnectionDecision.VALIDATION_FAILED
            }

            password == null -> {
                wipe(draftPassword)
                passwordDecision(removePasswordPending, stored)
            }

            else -> try {
                probeOutcome(settings, password!!)
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun preflightTestDecision(
        settings: WebDavPreferences,
        removePasswordPending: Boolean
    ): WebDavConnectionDecision? = when {
        !structureValid(settings) -> WebDavConnectionDecision.VALIDATION_FAILED
        settings.enabled && removePasswordPending -> WebDavConnectionDecision.VALIDATION_FAILED
        else -> null
    }

    private fun passwordDecision(
        removePasswordPending: Boolean,
        stored: WebDavStoredPassword?
    ): WebDavConnectionDecision = when {
        removePasswordPending || stored != WebDavStoredPassword.Invalid ->
            WebDavConnectionDecision.UNCONFIGURED

        else -> WebDavConnectionDecision.SECRET
    }

    private suspend fun probeOutcome(
        settings: WebDavPreferences,
        password: CharArray
    ): WebDavConnectionDecision =
        when (val result = probe.probe(settings.url, settings.username, password)) {
            WebDavProbeResult.Success -> WebDavConnectionDecision.SUCCESS
            WebDavProbeResult.Busy -> WebDavConnectionDecision.BUSY
            is WebDavProbeResult.Failed -> result.code.toConnectionDecision()
        }

    suspend fun syncNow(
        settings: WebDavPreferences,
        draftPassword: CharArray?,
        removePasswordPending: Boolean
    ): WebDavSyncNowDecision {
        val mutation: WebDavPasswordMutation = when {
            removePasswordPending -> WebDavPasswordMutation.Remove

            draftPassword != null && draftPassword.isNotEmpty() ->
                WebDavPasswordMutation.Replace(draftPassword)

            else -> WebDavPasswordMutation.Keep
        }
        if (!validForSave(settings, mutation)) {
            wipe(mutation)
            return WebDavSyncNowDecision.VALIDATION_FAILED
        }
        return when (val outcome = persistForSync(settings, mutation)) {
            is PersistOutcome.Saved -> when {
                !outcome.snapshot.enabled -> WebDavSyncNowDecision.DISABLED
                !outcome.snapshot.passwordConfigured -> WebDavSyncNowDecision.UNCONFIGURED
                else -> requestSyncNow(outcome.snapshot)
            }

            PersistOutcome.ValidationFailed -> WebDavSyncNowDecision.VALIDATION_FAILED

            PersistOutcome.SaveFailed -> WebDavSyncNowDecision.SAVE_FAILED
        }
    }

    fun cancelProbe() {
        probe.cancelInFlight()
    }

    private suspend fun persistForSync(
        settings: WebDavPreferences,
        mutation: WebDavPasswordMutation
    ): PersistOutcome = try {
        PersistOutcome.Saved(port.updateWebDav(settings, mutation))
    } catch (_: IllegalArgumentException) {
        PersistOutcome.ValidationFailed
    } catch (failure: CancellationException) {
        throw failure
    } catch (ignored: RuntimeException) {
        PersistOutcome.SaveFailed
    } finally {
        wipe(mutation)
    }

    private suspend fun validForSave(
        settings: WebDavPreferences,
        mutation: WebDavPasswordMutation
    ): Boolean = when {
        !structureValid(settings) -> false

        !settings.enabled -> true

        else -> when (mutation) {
            is WebDavPasswordMutation.Replace -> mutation.value.isNotEmpty()
            WebDavPasswordMutation.Remove -> false
            WebDavPasswordMutation.Keep -> persistedPasswordConfigured()
        }
    }
}

private fun structureValid(settings: WebDavPreferences): Boolean =
    runCatching { validateWebDavDraftOrNull(settings) }.isSuccess

private sealed interface PersistOutcome {
    data class Saved(val snapshot: WebDavSectionSnapshot) : PersistOutcome
    data object ValidationFailed : PersistOutcome
    data object SaveFailed : PersistOutcome
}

private fun wipe(array: CharArray?) {
    if (array != null) array.fill('\u0000')
}

private fun wipe(mutation: WebDavPasswordMutation) {
    if (mutation is WebDavPasswordMutation.Replace) {
        mutation.value.fill('\u0000')
    }
}

private fun SyncFailureCode.toConnectionDecision(): WebDavConnectionDecision = when (this) {
    SyncFailureCode.AUTH -> WebDavConnectionDecision.AUTH
    SyncFailureCode.DOCUMENT -> WebDavConnectionDecision.DOCUMENT
    SyncFailureCode.TRANSPORT -> WebDavConnectionDecision.TRANSPORT
    SyncFailureCode.SECRET -> WebDavConnectionDecision.SECRET
    SyncFailureCode.CANCELLED -> WebDavConnectionDecision.CANCELLED
    SyncFailureCode.CONFLICT -> WebDavConnectionDecision.TRANSPORT
    SyncFailureCode.SETTINGS, SyncFailureCode.INTERNAL -> WebDavConnectionDecision.INTERNAL
}
