package com.molotov.clender.alert

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.molotov.clender.app.alert.AlertKind
import com.molotov.clender.app.alert.blockedStatus

/** Runs only while an accepted important alarm is sounding; boot never starts this service. */
class ImportantAlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sessions: ImportantAlarmSessions
    private lateinit var platform: PlatformEventAlerts
    private val permissionCheck = object : Runnable {
        override fun run() {
            if (sessions.isEmpty) return
            try {
                checkPermissions()
            } catch (_: RuntimeException) {
                sessions.close()
            }
        }

        private fun checkPermissions() {
            val permissions = platform.permissions()
            when {
                !permissions.notificationsAllowed || !permissions.alarmChannelAllowed ->
                    sessions.close()

                platform.alarmSound() == null -> sessions.silence()

                else -> handler.postDelayed(this, PERMISSION_CHECK_MILLIS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        platform = PlatformEventAlerts(this)
        sessions = ImportantAlarmSessions(
            playerFactory = { MediaAlarmPlayer(this) },
            foreground = { request ->
                check(request.token.kind == AlertKind.ALARM)
                check(blockedStatus(request.token, platform.permissions()) == null)
                val summary = importantAlarmSummary(this, request.notification)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        FOREGROUND_ID,
                        summary,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(FOREGROUND_ID, summary)
                }
            },
            idle = {
                handler.removeCallbacks(permissionCheck)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            },
            failure = ImportantAlarmPlayback::reportFailure
        )
        ImportantAlarmPlayback.attach(sessions)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = ImportantAlarmPlayback.take(this, intent)
        if (request == null) {
            if (sessions.isEmpty) stopSelf()
        } else {
            sessions.add(request)
            handler.removeCallbacks(permissionCheck)
            if (!sessions.isEmpty) handler.postDelayed(permissionCheck, PERMISSION_CHECK_MILLIS)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(permissionCheck)
        ImportantAlarmPlayback.detach(sessions)
        sessions.close()
        super.onDestroy()
    }

    private companion object {
        const val FOREGROUND_ID = 66001
        const val PERMISSION_CHECK_MILLIS = 2_000L
    }
}
