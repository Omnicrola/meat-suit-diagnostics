package com.meatsuitdiagnostics.app.ui.common

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Whether the phone is set up to deliver reminders on time. */
data class ReminderReadiness(
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val unrestrictedBattery: Boolean,
) {
    val allGood: Boolean get() = notifications && exactAlarms && unrestrictedBattery
}

fun readReminderReadiness(context: Context) = ReminderReadiness(
    notifications = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
    exactAlarms = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
    unrestrictedBattery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
)

/** Re-read every time the screen resumes, e.g. after returning from a system settings page. */
@Composable
fun rememberReminderReadiness(): ReminderReadiness {
    val context = LocalContext.current
    var readiness by remember { mutableStateOf(readReminderReadiness(context)) }
    LifecycleResumeEffect(Unit) {
        readiness = readReminderReadiness(context)
        onPauseOrDispose { }
    }
    return readiness
}

@Composable
fun ReminderChecklist(readiness: ReminderReadiness, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Once denied twice Android stops showing the prompt, so send the user to the settings page instead.
        if (!granted) context.startActivity(appNotificationSettings(context))
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ChecklistItem(
            ok = readiness.notifications,
            title = "Notifications",
            detail = "Needed to remind you when a check-in is due.",
            actionLabel = "Allow",
            onAction = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        )
        ChecklistItem(
            ok = readiness.exactAlarms,
            title = "Exact alarms",
            detail = "Lets reminders arrive on time rather than whenever the phone next wakes up.",
            actionLabel = "Open settings",
            onAction = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                )
            },
        )
        ChecklistItem(
            ok = readiness.unrestrictedBattery,
            title = "Unrestricted battery use",
            detail = "Stops Android from delaying reminders to save battery.",
            actionLabel = "Allow",
            onAction = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                )
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Samsung phones", style = MaterialTheme.typography.titleSmall)
            Text(
                "Samsung puts unused apps to sleep on top of Android's own limits. Open Settings › Battery › " +
                    "Background usage limits › Never sleeping apps, and add Meat Suit Diagnostics.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { context.startActivity(appDetailsSettings(context)) }) {
                Text("Open app settings")
            }
        }
    }
}

@Composable
private fun ChecklistItem(ok: Boolean, title: String, detail: String, actionLabel: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = if (ok) "Done" else "Needs attention",
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp, end = 12.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!ok) FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private fun appNotificationSettings(context: Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun appDetailsSettings(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
