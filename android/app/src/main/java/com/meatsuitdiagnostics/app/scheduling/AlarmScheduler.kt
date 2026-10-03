package com.meatsuitdiagnostics.app.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.meatsuitdiagnostics.app.data.db.CheckinEntity
import com.meatsuitdiagnostics.app.domain.Schedule
import java.time.Instant
import java.time.ZonedDateTime

/**
 * Exact alarms for check-ins (one pending alarm per check-in: the next occurrence), for snoozes ending,
 * and for check-ins expiring. Each alarm's identity is its intent data URI, so setting it again replaces it.
 */
class AlarmScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun scheduleNext(checkin: CheckinEntity, after: ZonedDateTime) {
        if (checkin.daysOfWeek.isEmpty()) return
        val at = Schedule.nextOccurrence(checkin.timeLocal, checkin.daysOfWeek.toSet(), after).toInstant()
        setExact(at, firePending(checkin.id, at))
    }

    fun cancelCheckin(checkinId: Int) = alarms.cancel(firePending(checkinId, Instant.EPOCH))

    fun scheduleSnoozeEnd(instanceId: String, at: Instant) =
        setExact(at, instancePending(AlarmReceiver.ACTION_SNOOZE_END, instanceId))

    fun scheduleExpiry(instanceId: String, at: Instant) =
        setExact(at, instancePending(AlarmReceiver.ACTION_EXPIRE, instanceId))

    fun cancelInstance(instanceId: String) {
        alarms.cancel(instancePending(AlarmReceiver.ACTION_SNOOZE_END, instanceId))
        alarms.cancel(instancePending(AlarmReceiver.ACTION_EXPIRE, instanceId))
    }

    private fun setExact(at: Instant, pending: PendingIntent) {
        // USE_EXACT_ALARM is granted at install, so this is normally true; fall back to inexact just in case.
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
        }
    }

    private fun firePending(checkinId: Int, at: Instant): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .setData(Uri.parse("meatsuit-alarm://fire/$checkinId"))
            .putExtra(AlarmReceiver.EXTRA_CHECKIN_ID, checkinId)
            .putExtra(AlarmReceiver.EXTRA_SCHEDULED_FOR, at.toEpochMilli())
        return PendingIntent.getBroadcast(context, 0, intent, FLAGS)
    }

    private fun instancePending(action: String, instanceId: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("meatsuit-alarm://${action.substringAfterLast('.')}/$instanceId"))
            .putExtra(AlarmReceiver.EXTRA_INSTANCE_ID, instanceId)
        return PendingIntent.getBroadcast(context, 0, intent, FLAGS)
    }

    private companion object {
        const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
