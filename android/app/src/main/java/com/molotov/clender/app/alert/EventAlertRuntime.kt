package com.molotov.clender.app.alert

import com.molotov.clender.alert.AlertPermissionState
import com.molotov.clender.core.model.Event
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class EventAlertRuntime(
    private val events: Flow<List<Event>>,
    private val findEvent: suspend (Long) -> Event?,
    private val platform: EventAlertPlatform,
    private val ledger: AlertLedger,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) : AutoCloseable {
    internal val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow<Map<Long, AlertScheduleStatus>>(emptyMap())
    val status: StateFlow<Map<Long, AlertScheduleStatus>> = mutableStatus.asStateFlow()

    init {
        scope.launch {
            platform.playbackFailures.collect { token ->
                guarded {
                    mutex.withLock {
                        val event = findEvent(token.eventId)
                        if (event != null && token in AlertPlanFactory.tokens(event, zone()) &&
                            ledger.receipt(token) == AlertReceipt.DELIVERED
                        ) {
                            ledger.failed(token)
                            mutableStatus.setStatus(token.eventId, AlertScheduleStatus.FAILED)
                        }
                    }
                }
            }
        }
        scope.launch {
            try {
                events.collect { snapshot -> guarded { reconcile(snapshot) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                failKnownEvents()
            }
        }
    }

    fun refresh(): Job = scope.launch { guarded { reconcile(events.first()) } }

    fun handle(token: AlertToken, stop: Boolean = false): Job = scope.launch {
        guarded {
            mutex.withLock {
                if (stop) {
                    platform.dismiss(token.eventId)
                } else {
                    deliver(token)
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
        runBlocking { scopeJob.join() }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            withTimeout(OPERATION_TIMEOUT_MILLIS) { block() }
        } catch (_: TimeoutCancellationException) {
            failKnownEvents()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            failKnownEvents()
        }
    }

    // Platform and ledger failures must become visible scheduling failures.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun reconcile(snapshot: List<Event>) = mutex.withLock {
        try {
            rebuild(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            mutableStatus.value = snapshot.associate { it.id to AlertScheduleStatus.FAILED }
            throw failure
        }
    }

    private fun rebuild(snapshot: List<Event>) {
        val visible = snapshot.filter { it.deletedAt == null }
        val plans = visible.associate { event ->
            event.id to runCatching { AlertPlanFactory.tokens(event, zone()) }.getOrNull()
        }
        val allTokens = plans.values.filterNotNull().flatten().toSet()
        val previous = ledger.pending()
        val permission = platform.permissions()
        val next = mutableSetOf<AlertSchedule>()
        val statuses = mutableMapOf<Long, AlertScheduleStatus>()
        for (event in visible) {
            val tokens = plans[event.id]
            if (tokens == null) {
                statuses[event.id] = AlertScheduleStatus.FAILED
                continue
            }
            val future = tokens.filter {
                it.triggerAtMillis > clock.millis() &&
                    ledger.receipt(it) == null
            }
            val awaiting = previous.filter {
                it.token in tokens && awaitingDelivery(it.token, ledger, clock, permission)
            }
            // Preserve scheduled broadcasts during platform delay; never enqueue historical events.
            next += awaiting
            var status = awaiting.fold(initialStatus(tokens, future, ledger)) { current, plan ->
                stronger(current, scheduledStatus(plan))
            }
            for (token in future) {
                val blocked = blockedStatus(token, permission)
                if (blocked != null) {
                    status = stronger(status, blocked)
                    continue
                }
                val plan = AlertSchedule(token, permission.exactAllowed)
                try {
                    platform.schedule(plan)
                    next += plan
                    status = stronger(status, scheduledStatus(plan))
                } catch (_: RuntimeException) {
                    val failed =
                        blockedStatus(token, platform.permissions()) ?: AlertScheduleStatus.FAILED
                    status = stronger(status, failed)
                }
            }
            statuses[event.id] = status
        }
        cancelObsolete(previous, next, allTokens)
        ledger.savePending(next)
        mutableStatus.value = statuses
    }

    private fun cancelObsolete(
        previous: Set<AlertSchedule>,
        next: Set<AlertSchedule>,
        allTokens: Set<AlertToken>
    ) {
        for (old in previous) {
            if (old !in next) {
                // Replacing the same token's precision uses the same immutable platform identity.
                if (next.none { it.token == old.token }) platform.cancel(old.token)
                if (old.token !in allTokens) platform.dismiss(old.token.eventId)
            }
        }
        for (token in ledger.receipts().keys) {
            if (token !in allTokens) {
                platform.dismiss(token.eventId)
                ledger.release(token)
            }
        }
    }

    private suspend fun deliver(token: AlertToken) {
        val event = findEvent(token.eventId) ?: return
        if (!isCurrentDue(event, token, clock, zone())) return
        val blocked = blockedStatus(token, platform.permissions())
        if (blocked != null) {
            mutableStatus.setStatus(token.eventId, blocked)
        } else if (ledger.claim(token)) {
            showClaimed(event, token)
        }
    }

    private fun showClaimed(event: Event, token: AlertToken) {
        val shown = try {
            platform.show(event, token)
        } catch (_: RuntimeException) {
            false
        }
        if (shown) {
            ledger.delivered(token)
            mutableStatus.setStatus(token.eventId, AlertScheduleStatus.DELIVERED)
        } else {
            if (token.kind == AlertKind.ALARM) ledger.failed(token) else ledger.release(token)
            mutableStatus.setStatus(
                token.eventId,
                blockedStatus(token, platform.permissions()) ?: AlertScheduleStatus.FAILED
            )
        }
    }

    private fun failKnownEvents() {
        mutableStatus.value = mutableStatus.value.mapValues { AlertScheduleStatus.FAILED }
    }
}

private fun initialStatus(
    tokens: List<AlertToken>,
    future: List<AlertToken>,
    ledger: AlertLedger
): AlertScheduleStatus = when {
    tokens.isEmpty() -> AlertScheduleStatus.DISABLED

    tokens.any { ledger.receipt(it) in setOf(AlertReceipt.CLAIMED, AlertReceipt.FAILED) } ->
        AlertScheduleStatus.FAILED

    future.isNotEmpty() -> AlertScheduleStatus.SCHEDULED

    tokens.any { ledger.receipt(it) == AlertReceipt.DELIVERED } -> AlertScheduleStatus.DELIVERED

    else -> AlertScheduleStatus.PAST
}

private fun isCurrentDue(event: Event, token: AlertToken, clock: Clock, zone: ZoneId): Boolean =
    token in AlertPlanFactory.tokens(event, zone) && clock.millis() >= token.triggerAtMillis &&
        clock.millis() - token.triggerAtMillis <= MAX_DELIVERY_DELAY_MILLIS

private fun MutableStateFlow<Map<Long, AlertScheduleStatus>>.setStatus(
    eventId: Long,
    status: AlertScheduleStatus
) {
    value = value + (eventId to status)
}

private fun awaitingDelivery(
    token: AlertToken,
    ledger: AlertLedger,
    clock: Clock,
    permission: AlertPermissionState
): Boolean = clock.millis() - token.triggerAtMillis in 0..MAX_DELIVERY_DELAY_MILLIS &&
    ledger.receipt(token) == null && blockedStatus(token, permission) == null

private fun scheduledStatus(plan: AlertSchedule): AlertScheduleStatus =
    if (plan.exact) AlertScheduleStatus.SCHEDULED else AlertScheduleStatus.INEXACT

internal fun blockedStatus(
    token: AlertToken,
    permission: AlertPermissionState
): AlertScheduleStatus? = when {
    !permission.notificationsAllowed -> AlertScheduleStatus.NO_NOTIFICATIONS

    token.kind == AlertKind.ALARM && !permission.exactAllowed -> AlertScheduleStatus.NO_EXACT

    token.kind == AlertKind.NOTIFICATION && !permission.notificationChannelAllowed ->
        AlertScheduleStatus.CHANNEL_BLOCKED

    token.kind == AlertKind.ALARM && !permission.alarmChannelAllowed ->
        AlertScheduleStatus.CHANNEL_BLOCKED

    token.kind == AlertKind.TIMER && !permission.timerChannelAllowed ->
        AlertScheduleStatus.CHANNEL_BLOCKED

    else -> null
}

private fun stronger(first: AlertScheduleStatus, second: AlertScheduleStatus): AlertScheduleStatus {
    val priority = listOf(
        AlertScheduleStatus.DISABLED, AlertScheduleStatus.PAST, AlertScheduleStatus.DELIVERED,
        AlertScheduleStatus.SCHEDULED, AlertScheduleStatus.INEXACT, AlertScheduleStatus.NO_EXACT,
        AlertScheduleStatus.CHANNEL_BLOCKED,
        AlertScheduleStatus.NO_NOTIFICATIONS,
        AlertScheduleStatus.FAILED
    )
    return if (priority.indexOf(first) >= priority.indexOf(second)) first else second
}

private const val OPERATION_TIMEOUT_MILLIS = 8_000L
private const val MAX_DELIVERY_DELAY_MILLIS = 86_400_000L
