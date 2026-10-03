@file:OptIn(ExperimentalMaterial3Api::class)

package com.meatsuitdiagnostics.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.meatsuitdiagnostics.app.AppContainer
import com.meatsuitdiagnostics.app.BuildConfig
import com.meatsuitdiagnostics.app.data.settings.SyncStatus
import com.meatsuitdiagnostics.app.sync.SyncScheduler
import com.meatsuitdiagnostics.app.ui.common.BackButton
import com.meatsuitdiagnostics.app.ui.common.ReminderChecklist
import com.meatsuitdiagnostics.app.ui.common.SectionTitle
import com.meatsuitdiagnostics.app.ui.common.formatAgo
import com.meatsuitdiagnostics.app.ui.common.rememberReminderReadiness
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onRescan: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sync by container.settings.syncStatus.collectAsStateWithLifecycle(initialValue = SyncStatus())
    val credentials by container.settings.credentials.collectAsStateWithLifecycle(initialValue = null)
    val pending by container.checkins.pendingUploads.collectAsStateWithLifecycle(initialValue = 0)
    val failed by container.checkins.failedUploads.collectAsStateWithLifecycle(initialValue = emptyList())
    val syncWork by remember { SyncScheduler.observeSyncNow(context) }.collectAsStateWithLifecycle(initialValue = null)
    var confirmDiscard by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Sync")
            Text(
                sync.lastSuccessAt?.let { "Last synced ${formatAgo(it)}" } ?: "Not synced yet",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                if (pending == 0) "All answers uploaded" else "$pending answers waiting to upload",
                style = MaterialTheme.typography.bodyMedium,
            )
            sync.lastError?.let {
                Text("Last attempt failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val running = syncWork == WorkInfo.State.RUNNING
                Button(onClick = { container.syncNow() }, enabled = !running) { Text("Sync now") }
                when (syncWork) {
                    WorkInfo.State.RUNNING -> CircularProgressIndicator(Modifier.size(24.dp))
                    WorkInfo.State.ENQUEUED -> Text("Waiting for a connection…", style = MaterialTheme.typography.bodySmall)
                    else -> Unit
                }
            }

            if (failed.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "The server rejected ${failed.size} answer${if (failed.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        failed.map { it.error }.distinct().take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        Row {
                            TextButton(onClick = { scope.launch { container.checkins.retryFailedUploads() } }) { Text("Retry") }
                            TextButton(onClick = { confirmDiscard = true }) { Text("Discard") }
                        }
                    }
                }
            }

            SectionTitle("Server")
            Text(credentials?.serverUrl ?: "Not connected", style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = onRescan) { Text("Scan a new setup code") }

            SectionTitle("Reminders")
            ReminderChecklist(rememberReminderReadiness())

            SectionTitle("About")
            Text("Meat Suit Diagnostics ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard rejected answers?") },
            text = { Text("They'll be deleted from this phone and never uploaded.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    scope.launch { container.checkins.discardFailedUploads() }
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } },
        )
    }
}
