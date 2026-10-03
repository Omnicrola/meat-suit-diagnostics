@file:OptIn(ExperimentalMaterial3Api::class)

package com.meatsuitdiagnostics.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meatsuitdiagnostics.app.AppContainer
import com.meatsuitdiagnostics.app.data.db.CheckinEntity
import com.meatsuitdiagnostics.app.data.db.InstanceEntity
import com.meatsuitdiagnostics.app.data.settings.SyncStatus
import com.meatsuitdiagnostics.app.domain.Schedule
import com.meatsuitdiagnostics.app.ui.common.SectionTitle
import com.meatsuitdiagnostics.app.ui.common.WarningCard
import com.meatsuitdiagnostics.app.ui.common.formatAgo
import com.meatsuitdiagnostics.app.ui.common.formatDayAndTime
import com.meatsuitdiagnostics.app.ui.common.formatTime
import com.meatsuitdiagnostics.app.ui.common.rememberReminderReadiness
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class Upcoming(val name: String, val at: Instant)

data class HomeState(
    val open: List<InstanceEntity> = emptyList(),
    val upcoming: List<Upcoming> = emptyList(),
    val pendingUploads: Int = 0,
    val sync: SyncStatus = SyncStatus(),
    val now: Instant = Instant.now(),
)

class HomeViewModel(container: AppContainer) : ViewModel() {
    /** Re-evaluates relative times ("5 min ago", next occurrence) while the screen is open. */
    private val clock = flow {
        while (true) {
            emit(Instant.now())
            delay(30_000)
        }
    }

    val state: StateFlow<HomeState> = combine(
        container.checkins.openInstances,
        container.config.checkins,
        container.checkins.pendingUploads,
        container.settings.syncStatus,
        clock,
    ) { open, checkins, pending, sync, now ->
        HomeState(open, upcoming(checkins, now), pending, sync, now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    private fun upcoming(checkins: List<CheckinEntity>, now: Instant): List<Upcoming> {
        val zonedNow = now.atZone(ZoneId.systemDefault())
        return checkins
            .filter { it.daysOfWeek.isNotEmpty() }
            .map { Upcoming(it.name, Schedule.nextOccurrence(it.timeLocal, it.daysOfWeek.toSet(), zonedNow).toInstant()) }
            .sortedBy { it.at }
            .take(4)
    }
}

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenCheckin: (String) -> Unit,
    onAnswerNow: () -> Unit,
    onSettings: () -> Unit,
    onRescan: () -> Unit,
) {
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val readiness = rememberReminderReadiness()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meat Suit Diagnostics") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.sync.authFailed) {
                item {
                    WarningCard(
                        title = "The server rejected this phone's key",
                        detail = "Your answers are kept on the phone until you reconnect.",
                        actionLabel = "Scan a new setup code",
                        onAction = onRescan,
                    )
                }
            }
            if (!readiness.allGood) {
                item {
                    WarningCard(
                        title = "Reminders might not arrive on time",
                        detail = "Some permissions are missing.",
                        actionLabel = "Fix in settings",
                        onAction = onSettings,
                    )
                }
            }

            item { SectionTitle("Waiting for you") }
            if (state.open.isEmpty()) {
                item { MutedText("Nothing to answer right now.") }
            }
            items(state.open, key = { it.id }) { instance ->
                OpenCheckinCard(instance, onClick = { onOpenCheckin(instance.id) })
            }

            item { SectionTitle("Coming up") }
            if (state.upcoming.isEmpty()) {
                item { MutedText("No check-ins are scheduled. Add some in the admin web app.") }
            }
            items(state.upcoming) { upcoming ->
                Row(Modifier.fillMaxWidth()) {
                    Text(upcoming.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(formatDayAndTime(upcoming.at, state.now), style = MaterialTheme.typography.bodyLarge)
                }
            }

            item {
                Button(onClick = onAnswerNow, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Answer a question now")
                }
            }

            item { SyncFooter(state) }
        }
    }
}

@Composable
private fun OpenCheckinCard(instance: InstanceEntity, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(instance.checkinName, style = MaterialTheme.typography.titleMedium)
            val timing = if (instance.status == InstanceEntity.STATUS_SNOOZED && instance.snoozedUntil != null) {
                "Snoozed until ${formatTime(instance.snoozedUntil)}"
            } else {
                "Due ${formatTime(instance.scheduledFor)} · closes ${formatTime(instance.expiresAt)}"
            }
            Text(timing, style = MaterialTheme.typography.bodyMedium)
            val count = instance.questionCount
            MutedText(if (count == 1) "1 question" else "$count questions")
        }
    }
}

@Composable
private fun SyncFooter(state: HomeState) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MutedText(
            when (state.pendingUploads) {
                0 -> "All answers uploaded"
                1 -> "1 answer waiting to upload"
                else -> "${state.pendingUploads} answers waiting to upload"
            }
        )
        state.sync.lastSuccessAt?.let { MutedText("Last synced ${formatAgo(it, state.now)}") }
        if (state.sync.lastError != null && !state.sync.authFailed) {
            Text("Last sync failed: ${state.sync.lastError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MutedText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
