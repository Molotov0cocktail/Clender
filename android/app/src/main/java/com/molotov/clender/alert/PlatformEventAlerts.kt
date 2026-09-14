package com.molotov.clender.alert

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import com.molotov.clender.R
import com.molotov.clender.app.alert.AlertKind
import com.molotov.clender.app.alert.AlertSchedule
import com.molotov.clender.app.alert.AlertToken
import com.molotov.clender.app.alert.EventAlertPlatform
import com.molotov.clender.app.alert.blockedStatus
import com.molotov.clender.core.model.Event
import com.molotov.clender.data.settings.AlarmSoundStore
import java.io.File
import kotlinx.coroutines.flow.Flow

class PlatformEventAlerts(
    private val context: Context,
    private val alarmDelivery: ImportantAlarmDelivery = ForegroundAlarmDelivery(context)
) : EventAlertPlatform {
    override val playbackFailures: Flow<AlertToken> get() = alarmDelivery.failures

    fun alarmSound(): Uri? {
        val channelSound = notifications.getNotificationChannel(channelId(AlertKind.ALARM))
            ?.takeIf { it.importance >= NotificationManager.IMPORTANCE_DEFAULT }?.sound
            ?: return null
        val selected = AlarmSoundStore(File(context.filesDir, "alarm-sound")).selectedFile()
        return selected?.let(Uri::fromFile) ?: channelSound
    }

    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    init {
        AlertKind.entries.forEach { kind ->
            val important = kind == AlertKind.ALARM
            val channel = NotificationChannel(
                channelId(kind),
                context.getString(channelName(kind)),
                if (important || kind == AlertKind.TIMER) {
                    NotificationManager.IMPORTANCE_HIGH
                } else {
                    NotificationManager.IMPORTANCE_DEFAULT
                }
            )
            channel.setSound(
                RingtoneManager.getDefaultUri(
                    if (important) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION
                ),
                AudioAttributes.Builder()
                    .setUsage(
                        if (important) {
                            AudioAttributes.USAGE_ALARM
                        } else {
                            AudioAttributes.USAGE_NOTIFICATION
                        }
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            try {
                notifications.createNotificationChannel(channel)
            } catch (_: RuntimeException) {
                // Missing channels are blocked; saving the event must still succeed.
            }
        }
    }

    override fun permissions(): AlertPermissionState = AlertPermissionState(
        notificationsAllowed = notifications.areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                ),
        exactAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms(),
        notificationChannelAllowed = channelAllowed(AlertKind.NOTIFICATION),
        alarmChannelAllowed = channelAllowed(AlertKind.ALARM),
        timerChannelAllowed = channelAllowed(AlertKind.TIMER)
    )

    override fun schedule(plan: AlertSchedule) {
        check(blockedStatus(plan.token, permissions()) == null)
        val pending = AlertPendingIntents.broadcast(context, plan.token)
        if (plan.token.kind == AlertKind.ALARM) {
            alarms.setAlarmClock(
                AlarmManager.AlarmClockInfo(
                    plan.token.triggerAtMillis,
                    AlertPendingIntents.openApp(context)
                ),
                pending
            )
        } else if (plan.exact) {
            alarms.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                plan.token.triggerAtMillis,
                pending
            )
        } else {
            alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                plan.token.triggerAtMillis,
                pending
            )
        }
    }

    override fun cancel(token: AlertToken) {
        val pending = AlertPendingIntents.broadcast(context, token)
        alarms.cancel(pending)
        pending.cancel()
    }

    override fun show(event: Event, token: AlertToken): Boolean {
        if (blockedStatus(token, permissions()) != null) return false
        val important = token.kind == AlertKind.ALARM
        val stop = AlertPendingIntents.broadcast(context, token, stop = true)
        val description = context.getString(channelName(token.kind)) +
            " · " + event.startTime.toString().replace('T', ' ')
        val builder = Notification.Builder(context, channelId(token.kind))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(event.title)
            .setContentText(description)
            .setWhen(token.triggerAtMillis)
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(
                if (important) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER
            )
            .setOnlyAlertOnce(true)
            .setAutoCancel(!important)
            .setContentIntent(AlertPendingIntents.openApp(context))
            .setDeleteIntent(stop)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.event_alert_stop),
                    stop
                ).build()
            )
        if (important) {
            // This child has no summary: the foreground player owns the alarm sound on API 26+.
            builder.setGroup("clender_important_alarms")
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        }
        val notification = builder.build()
        val started = !important || alarmDelivery.start(event, token, notification, alarmSound())
        val visible = if (started) {
            notification
        } else {
            builder.setContentText(context.getString(R.string.event_alert_sound_failed)).build()
        }
        runCatching {
            notifications.notify(tag(event.id), token.kind.ordinal + 1, visible)
        }.onFailure {
            if (important) alarmDelivery.stop(event.id)
        }.getOrThrow()
        return started
    }

    override fun dismiss(eventId: Long) {
        alarmDelivery.stop(eventId)
        AlertKind.entries.forEach { notifications.cancel(tag(eventId), it.ordinal + 1) }
    }

    private fun channelAllowed(kind: AlertKind): Boolean =
        notifications.getNotificationChannel(channelId(kind))?.let {
            it.importance != NotificationManager.IMPORTANCE_NONE
        } ?: false

    private fun tag(eventId: Long): String = "clender_event_$eventId"

    private fun channelId(kind: AlertKind): String =
        "clender_${kind.name.lowercase(java.util.Locale.ROOT)}_v1"

    private fun channelName(kind: AlertKind): Int = when (kind) {
        AlertKind.NOTIFICATION -> R.string.event_alert_notification_channel
        AlertKind.ALARM -> R.string.event_alert_alarm_channel
        AlertKind.TIMER -> R.string.event_alert_timer_channel
    }
}
