package com.molotov.clender.alert

import android.app.Notification
import android.content.Context
import com.molotov.clender.R

internal fun importantAlarmSummary(
    context: Context,
    eventNotification: Notification
): Notification = Notification.Builder(context, eventNotification.channelId)
    .setSmallIcon(R.drawable.ic_launcher_monochrome)
    .setContentTitle(context.getString(R.string.important_alarm_playback_title))
    .setContentText(context.getString(R.string.important_alarm_playback_description))
    .setContentIntent(eventNotification.contentIntent)
    .setVisibility(Notification.VISIBILITY_PRIVATE)
    .setGroup("clender_important_alarms")
    .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
    .setOnlyAlertOnce(true)
    .setOngoing(true)
    .setCategory(Notification.CATEGORY_SERVICE)
    .build()
