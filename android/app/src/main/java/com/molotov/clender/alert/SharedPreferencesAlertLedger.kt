package com.molotov.clender.alert

import android.annotation.SuppressLint
import android.content.Context
import com.molotov.clender.app.alert.AlertKind
import com.molotov.clender.app.alert.AlertLedger
import com.molotov.clender.app.alert.AlertReceipt
import com.molotov.clender.app.alert.AlertSchedule
import com.molotov.clender.app.alert.AlertToken
import java.time.Instant

/** Synchronous private receipts survive process death; no event text or account data is stored. */
// KTX edit(commit = true) discards the Boolean commit failure result checked below.
@SuppressLint("UseKtx")
class SharedPreferencesAlertLedger(context: Context) : AlertLedger {
    private val preferences = context.getSharedPreferences(
        "clender_event_alerts",
        Context.MODE_PRIVATE
    )

    @Synchronized
    override fun pending(): Set<AlertSchedule> =
        preferences.getStringSet(PENDING, emptySet()).orEmpty()
            .mapNotNull { encoded ->
                val parts = encoded.split(DELIMITER)
                if (parts.size != SCHEDULE_PARTS) return@mapNotNull null
                val token = decode(parts.take(TOKEN_PARTS)) ?: return@mapNotNull null
                val exact = parts.last().toBooleanStrictOrNull() ?: return@mapNotNull null
                AlertSchedule(token, exact)
            }.toSet()

    @Synchronized
    override fun savePending(value: Set<AlertSchedule>) {
        val encoded = value.map { encode(it.token) + "|" + it.exact }.toSet()
        check(preferences.edit().putStringSet(PENDING, encoded).commit())
    }

    @Synchronized
    override fun receipts(): Map<AlertToken, AlertReceipt> =
        preferences.all.mapNotNull { (key, value) ->
            if (!key.startsWith(RECEIPT_PREFIX) || value !is String) return@mapNotNull null
            val parts = value.split(DELIMITER)
            if (parts.size != SCHEDULE_PARTS) return@mapNotNull null
            val token = decode(parts.take(TOKEN_PARTS)) ?: return@mapNotNull null
            val state =
                runCatching { AlertReceipt.valueOf(parts.last()) }.getOrNull()
                    ?: return@mapNotNull null
            if (key != receiptKey(token)) return@mapNotNull null
            token to state
        }.toMap()

    @Synchronized
    override fun receipt(token: AlertToken): AlertReceipt? = receipts()[token]

    @Synchronized
    override fun claim(token: AlertToken): Boolean {
        if (receipt(token) != null) return false
        writeReceipt(token, AlertReceipt.CLAIMED)
        return true
    }

    @Synchronized
    override fun failed(token: AlertToken) = writeReceipt(token, AlertReceipt.FAILED)

    @Synchronized
    override fun delivered(token: AlertToken) = writeReceipt(token, AlertReceipt.DELIVERED)

    @Synchronized
    override fun release(token: AlertToken) {
        if (receipt(token) != null) check(preferences.edit().remove(receiptKey(token)).commit())
    }

    private fun writeReceipt(token: AlertToken, state: AlertReceipt) {
        check(
            preferences.edit().putString(
                receiptKey(token),
                encode(token) + "|" + state.name
            ).commit()
        )
    }
}

private fun receiptKey(token: AlertToken): String =
    "$RECEIPT_PREFIX${token.eventId}_${token.kind.name}"

private fun encode(token: AlertToken): String =
    "${token.eventId}|${token.kind.name}|${token.triggerAtMillis}|${token.revision}"

private fun decode(parts: List<String>): AlertToken? = runCatching {
    val id = parts[0].toLong().also { require(it > 0) }
    val kind = AlertKind.valueOf(parts[1])
    val trigger = parts[2].toLong().also { require(it >= 0) }
    Instant.parse(parts[REVISION_INDEX])
    AlertToken(id, kind, trigger, parts[REVISION_INDEX])
}.getOrNull()

private const val PENDING = "pending"
private const val RECEIPT_PREFIX = "receipt_"
private const val DELIMITER = '|'
private const val TOKEN_PARTS = 4
private const val REVISION_INDEX = 3
private const val SCHEDULE_PARTS = 5
