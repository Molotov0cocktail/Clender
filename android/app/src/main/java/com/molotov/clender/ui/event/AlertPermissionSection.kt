package com.molotov.clender.ui.event

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.molotov.clender.R
import com.molotov.clender.alert.AlertPermissionState
import com.molotov.clender.alert.PlatformEventAlerts

@Composable
fun AlertPermissionSection() {
    val context = LocalContext.current
    val platform = remember(context) { PlatformEventAlerts(context.applicationContext) }
    val runtime = LocalEventAlertRuntime.current
    var permissions by remember(platform) { mutableStateOf(platform.permissions()) }
    var unavailable by remember { mutableStateOf(false) }
    val refresh = {
        permissions = platform.permissions()
        runtime?.refresh()
        Unit
    }
    val settings =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refresh()
        }
    val notification =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, platform, runtime) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val openSettings: (Intent) -> Unit = { intent ->
        try {
            settings.launch(intent)
            unavailable = false
        } catch (_: ActivityNotFoundException) {
            unavailable = true
        } catch (_: SecurityException) {
            unavailable = true
        }
    }
    AlertPermissionContent(
        permissions,
        unavailable,
        onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notification.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onSettings = { target -> openSettings(alertSettingsIntent(target, context.packageName)) }
    )
}

@Composable
private fun AlertPermissionContent(
    permissions: AlertPermissionState,
    unavailable: Boolean,
    onRequestNotifications: () -> Unit,
    onSettings: (AlertSettingsTarget) -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.alert_permissions_title),
            style = MaterialTheme.typography.titleMedium
        )
        PermissionExplanation(permissions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !permissions.notificationsAllowed
        ) {
            PermissionButton(
                R.string.alert_grant_notifications,
                "event_alert_request_notification",
                onRequestNotifications
            )
        }
        PermissionButton(
            R.string.alert_notification_settings,
            "event_alert_notifications_settings"
        ) {
            onSettings(AlertSettingsTarget.NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PermissionButton(R.string.alert_exact_settings, "event_alert_exact_settings") {
                onSettings(AlertSettingsTarget.EXACT)
            }
        }
        Text(
            stringResource(R.string.alert_background_explanation),
            style = MaterialTheme.typography.bodySmall
        )
        PermissionButton(R.string.alert_battery_settings, "event_alert_background_settings") {
            onSettings(AlertSettingsTarget.BATTERY)
        }
        PermissionButton(R.string.alert_app_settings, "event_alert_app_settings") {
            onSettings(AlertSettingsTarget.APP)
        }
        if (unavailable) {
            Text(
                stringResource(R.string.alert_settings_unavailable),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PermissionExplanation(permissions: AlertPermissionState) {
    val notificationLabel = if (permissions.notificationsAllowed) {
        R.string.alert_notifications_ready
    } else {
        R.string.alert_notifications_blocked
    }
    Text(stringResource(notificationLabel))
    Text(
        stringResource(
            if (permissions.exactAllowed) {
                R.string.alert_exact_ready
            } else {
                R.string.alert_exact_blocked
            }
        )
    )
    if (!permissions.notificationChannelAllowed || !permissions.alarmChannelAllowed ||
        !permissions.timerChannelAllowed
    ) {
        Text(
            stringResource(R.string.alert_channel_blocked),
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun PermissionButton(label: Int, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag(tag)
    ) { Text(stringResource(label)) }
}

internal enum class AlertSettingsTarget { NOTIFICATIONS, EXACT, BATTERY, APP }

internal fun alertSettingsIntent(target: AlertSettingsTarget, packageName: String): Intent =
    when (target) {
        AlertSettingsTarget.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

        AlertSettingsTarget.EXACT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:$packageName".toUri())
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
        }

        AlertSettingsTarget.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        AlertSettingsTarget.APP -> Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$packageName".toUri()
        )
    }
