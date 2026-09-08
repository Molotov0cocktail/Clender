package com.molotov.clender.app.alert

import com.molotov.clender.alert.AlertPermissionState
import com.molotov.clender.core.model.Event
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

enum class AlertKind { NOTIFICATION, ALARM, TIMER }

enum class AlertScheduleStatus {
    SCHEDULED,
    INEXACT,
    NO_NOTIFICATIONS,
    NO_EXACT,
    CHANNEL_BLOCKED,
    FAILED,
    DISABLED,
    PAST,
    DELIVERED
}

enum class AlertReceipt { CLAIMED, DELIVERED, FAILED }

data class AlertToken(
    val eventId: Long,
    val kind: AlertKind,
    val triggerAtMillis: Long,
    val revision: String
)

data class AlertSchedule(val token: AlertToken, val exact: Boolean)

interface EventAlertPlatform {
    val playbackFailures: Flow<AlertToken> get() = emptyFlow()
    fun permissions(): AlertPermissionState
    fun schedule(plan: AlertSchedule)
    fun cancel(token: AlertToken)
    fun show(event: Event, token: AlertToken): Boolean
    fun dismiss(eventId: Long)
}

interface AlertLedger {
    fun pending(): Set<AlertSchedule>
    fun savePending(value: Set<AlertSchedule>)
    fun receipts(): Map<AlertToken, AlertReceipt>
    fun receipt(token: AlertToken): AlertReceipt?
    fun claim(token: AlertToken): Boolean
    fun failed(token: AlertToken)
    fun delivered(token: AlertToken)
    fun release(token: AlertToken)
}
