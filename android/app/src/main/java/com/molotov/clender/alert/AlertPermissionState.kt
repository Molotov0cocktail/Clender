package com.molotov.clender.alert

data class AlertPermissionState(
    val notificationsAllowed: Boolean,
    val exactAllowed: Boolean,
    val notificationChannelAllowed: Boolean,
    val alarmChannelAllowed: Boolean,
    val timerChannelAllowed: Boolean
)
