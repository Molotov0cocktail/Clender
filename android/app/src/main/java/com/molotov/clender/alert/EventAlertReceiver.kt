package com.molotov.clender.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.molotov.clender.app.alert.EventAlertRuntime
import java.util.concurrent.atomic.AtomicBoolean

interface EventAlertRuntimeOwner {
    val alertRuntime: EventAlertRuntime
}

class EventAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = AlertPendingIntents.validate(context, intent) ?: return
        val finish = AlertReceiverFinisher(goAsync())
        try {
            val owner = context.applicationContext as? EventAlertRuntimeOwner
            if (owner == null) {
                finish.finish()
            } else {
                owner.alertRuntime.handle(action.token, action.stop).invokeOnCompletion {
                    finish.finish()
                }
            }
        } catch (_: RuntimeException) {
            finish.finish()
        }
    }
}

internal class AlertReceiverFinisher(private val pending: BroadcastReceiver.PendingResult) {
    private val finished = AtomicBoolean(false)
    fun finish() {
        if (finished.compareAndSet(false, true)) pending.finish()
    }
}
