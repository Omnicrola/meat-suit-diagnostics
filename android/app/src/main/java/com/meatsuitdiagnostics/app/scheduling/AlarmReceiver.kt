package com.meatsuitdiagnostics.app.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meatsuitdiagnostics.app.MeatSuitApp
import com.meatsuitdiagnostics.app.data.CheckinRepository
import com.meatsuitdiagnostics.app.data.db.ConfigDao
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

/** Receives check-in alarms, snooze/expiry alarms and the notification's snooze buttons. */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as MeatSuitApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.alarmHandler.handle(intent)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.meatsuitdiagnostics.app.action.FIRE"
        const val ACTION_SNOOZE = "com.meatsuitdiagnostics.app.action.SNOOZE"
        const val ACTION_SNOOZE_END = "com.meatsuitdiagnostics.app.action.SNOOZE_END"
        const val ACTION_EXPIRE = "com.meatsuitdiagnostics.app.action.EXPIRE"
        const val EXTRA_CHECKIN_ID = "checkin_id"
        const val EXTRA_SCHEDULED_FOR = "scheduled_for"
        const val EXTRA_INSTANCE_ID = "instance_id"
        const val EXTRA_MINUTES = "minutes"
    }
}

class AlarmHandler(
    private val checkins: CheckinRepository,
    private val configDao: ConfigDao,
    private val scheduler: AlarmScheduler,
    private val notifier: Notifier,
) {
    suspend fun handle(intent: Intent) {
        when (intent.action) {
            AlarmReceiver.ACTION_FIRE -> onFire(
                checkinId = intent.getIntExtra(AlarmReceiver.EXTRA_CHECKIN_ID, -1),
                scheduledFor = Instant.ofEpochMilli(intent.getLongExtra(AlarmReceiver.EXTRA_SCHEDULED_FOR, 0)),
            )
            AlarmReceiver.ACTION_SNOOZE -> checkins.snooze(
                instanceId = intent.getStringExtra(AlarmReceiver.EXTRA_INSTANCE_ID) ?: return,
                minutes = intent.getLongExtra(AlarmReceiver.EXTRA_MINUTES, 30),
            )
            AlarmReceiver.ACTION_SNOOZE_END ->
                checkins.endSnooze(intent.getStringExtra(AlarmReceiver.EXTRA_INSTANCE_ID) ?: return)
            AlarmReceiver.ACTION_EXPIRE -> checkins.expireDue()
        }
    }

    private suspend fun onFire(checkinId: Int, scheduledFor: Instant) {
        val now = Instant.now()
        // Close the previous occurrence first if it's still open (its expiry is at the latest this moment).
        checkins.expireDue(now)
        val checkin = configDao.checkin(checkinId) ?: return
        // Schedule the following occurrence before anything else, so the series continues whatever happens below.
        scheduler.scheduleNext(checkin, maxOf(now, scheduledFor).atZone(ZoneId.systemDefault()))

        val instance = checkins.fire(checkinId, scheduledFor) ?: return
        if (instance.expiresAt <= now.toEpochMilli()) {
            // Delivered too late to answer (e.g. the phone was off): record it as missed straight away.
            checkins.expireDue(now)
            return
        }
        notifier.show(instance)
        scheduler.scheduleExpiry(instance.id, Instant.ofEpochMilli(instance.expiresAt))
    }
}
