package com.meatsuitdiagnostics.app.scheduling

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.meatsuitdiagnostics.app.MainActivity
import com.meatsuitdiagnostics.app.R
import com.meatsuitdiagnostics.app.data.db.InstanceEntity

/** Check-in reminders. One notification per check-in (a check-in only has one open occurrence at a time). */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Check-ins", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Reminders to answer a check-in"
        }
        manager.createNotificationChannel(channel)
    }

    fun show(instance: InstanceEntity) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val remaining = instance.expiresAt - System.currentTimeMillis()
        if (remaining <= 0) return

        val open = PendingIntent.getActivity(
            context, 0, MainActivity.openCheckinIntent(context, instance.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val count = instance.questionCount
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(instance.checkinName)
            .setContentText(if (count == 1) "1 question to answer" else "$count questions to answer")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(remaining)
        if (!instance.snoozed) {
            builder.addAction(0, "Snooze 30 min", snoozeIntent(instance.id, 30))
            builder.addAction(0, "Snooze 60 min", snoozeIntent(instance.id, 60))
        }
        manager.notify(notificationId(instance), builder.build())
    }

    fun cancel(instance: InstanceEntity) = manager.cancel(notificationId(instance))

    private fun notificationId(instance: InstanceEntity) = instance.checkinId

    private fun snoozeIntent(instanceId: String, minutes: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_SNOOZE)
            .setData("meatsuit-alarm://snooze/$instanceId/$minutes".toUri())
            .putExtra(AlarmReceiver.EXTRA_INSTANCE_ID, instanceId)
            .putExtra(AlarmReceiver.EXTRA_MINUTES, minutes)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        const val CHANNEL_ID = "checkins"
    }
}
