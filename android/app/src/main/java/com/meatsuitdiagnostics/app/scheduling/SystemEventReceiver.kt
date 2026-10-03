package com.meatsuitdiagnostics.app.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meatsuitdiagnostics.app.MeatSuitApp
import kotlinx.coroutines.launch

/**
 * Rebuilds alarms when they may have been lost or become wrong: after a reboot or app update (alarms are
 * cleared), and when the time or timezone changes (check-in times are local wall-clock times).
 */
class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val container = (context.applicationContext as MeatSuitApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.checkins.expireDue()
                container.config.rescheduleAll()
                container.checkins.restoreAlarms()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED, // "android.intent.action.TIME_SET"
        )
    }
}
