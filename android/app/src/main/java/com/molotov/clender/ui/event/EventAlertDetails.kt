package com.molotov.clender.ui.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.molotov.clender.R
import com.molotov.clender.app.alert.AlertPlanFactory
import com.molotov.clender.app.alert.AlertScheduleStatus
import com.molotov.clender.core.model.Event
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val TIMER_DISPLAY_INTERVAL_MILLIS = 60_000L
private const val SECONDS_PER_MINUTE = 60

@Composable
internal fun EventAlertDetails(event: Event) {
    val status = LocalEventAlertRuntime.current?.status?.collectAsStateWithLifecycle()?.value?.get(
        event.id
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.alert_section_title),
            style = MaterialTheme.typography.titleMedium
        )
        val mode = when {
            event.alarmEnabled -> R.string.alert_mode_alarm
            event.notificationEnabled -> R.string.alert_mode_notification
            else -> R.string.alert_mode_off
        }
        Text(stringResource(mode), Modifier.testTag("event_alert_selected_mode"))
        status?.let {
            Text(
                stringResource(alertStatusResource(it)),
                Modifier.testTag("event_alert_schedule_status")
            )
        }
        if (event.timerMinutes > 0) TimerStatus(event)
    }
}

@Composable
private fun TimerStatus(event: Event) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(Instant.now(), lifecycle, event.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = Instant.now()
                delay(TIMER_DISPLAY_INTERVAL_MILLIS)
            }
        }
    }
    val zone = ZoneId.systemDefault()
    val deadline = AlertPlanFactory.timerDeadline(event, zone) ?: return
    val locale = LocalConfiguration.current.locales[0]
    val formatted = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale).format(deadline.atZone(zone))
    val remaining = timerRemainingMinutes(deadline, now)
    val message = when {
        now.isBefore(event.startTime.atZone(zone).toInstant()) ->
            stringResource(R.string.alert_timer_waiting, formatted)

        remaining > 0 -> stringResource(R.string.alert_timer_running, remaining, formatted)

        else -> stringResource(R.string.alert_timer_finished, formatted)
    }
    Text(message, Modifier.testTag("event_alert_timer_status"))
}

internal fun timerRemainingMinutes(deadline: Instant, now: Instant): Long {
    if (!deadline.isAfter(now)) return 0
    val duration = Duration.between(now, deadline)
    return duration.seconds / SECONDS_PER_MINUTE +
        if (duration.seconds % SECONDS_PER_MINUTE != 0L || duration.nano != 0) 1 else 0
}

private fun alertStatusResource(status: AlertScheduleStatus): Int = when (status) {
    AlertScheduleStatus.SCHEDULED -> R.string.alert_status_scheduled
    AlertScheduleStatus.INEXACT -> R.string.alert_status_inexact
    AlertScheduleStatus.NO_NOTIFICATIONS -> R.string.alert_notifications_blocked
    AlertScheduleStatus.NO_EXACT -> R.string.alert_status_no_exact
    AlertScheduleStatus.CHANNEL_BLOCKED -> R.string.alert_channel_blocked
    AlertScheduleStatus.FAILED -> R.string.alert_status_failed
    AlertScheduleStatus.DISABLED -> R.string.alert_mode_off
    AlertScheduleStatus.PAST -> R.string.alert_status_past
    AlertScheduleStatus.DELIVERED -> R.string.alert_status_delivered
}
