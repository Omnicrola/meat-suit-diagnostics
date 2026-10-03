@file:OptIn(ExperimentalMaterial3Api::class)

package com.meatsuitdiagnostics.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.meatsuitdiagnostics.app.AppContainer
import com.meatsuitdiagnostics.app.BuildConfig
import com.meatsuitdiagnostics.app.data.api.AuthException
import com.meatsuitdiagnostics.app.data.api.ConfigResult
import com.meatsuitdiagnostics.app.data.settings.Credentials
import com.meatsuitdiagnostics.app.domain.SetupLink
import com.meatsuitdiagnostics.app.ui.common.BackButton
import com.meatsuitdiagnostics.app.ui.common.ReminderChecklist
import com.meatsuitdiagnostics.app.ui.common.rememberReminderReadiness
import java.io.IOException
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

sealed interface SetupState {
    data class Scan(val error: String? = null) : SetupState
    data class Confirm(val link: SetupLink, val error: String? = null) : SetupState
    data class Connecting(val link: SetupLink) : SetupState
    data object Permissions : SetupState
}

class SetupViewModel(private val container: AppContainer) : ViewModel() {
    var state by mutableStateOf<SetupState>(SetupState.Scan())
        private set

    init {
        // A setup link opened from outside the app (camera app, browser) waits here for confirmation.
        viewModelScope.launch {
            container.pendingSetupLink.collect { link ->
                if (link != null) {
                    state = SetupState.Confirm(link)
                    container.pendingSetupLink.value = null
                }
            }
        }
    }

    fun onScanned(raw: String?) {
        val link = raw?.let { SetupLink.parse(it, BuildConfig.DEBUG) }
        state = if (link == null) {
            SetupState.Scan(error = "That QR code isn't a Meat Suit Diagnostics setup code.")
        } else {
            SetupState.Confirm(link)
        }
    }

    fun onScanFailed(message: String) {
        state = SetupState.Scan(error = message)
    }

    fun cancel() {
        state = SetupState.Scan()
    }

    /** Checks the key against the server before saving anything, so a bad code never replaces a good one. */
    fun connect(link: SetupLink) {
        state = SetupState.Connecting(link)
        viewModelScope.launch {
            val credentials = Credentials(link.serverUrl, link.apiKey)
            state = try {
                val result = container.api.getConfig(credentials, etag = null)
                container.settings.saveCredentials(credentials)
                if (result is ConfigResult.Updated) {
                    container.config.apply(result.config)
                    container.settings.setConfigEtag(result.etag)
                }
                container.settings.recordSyncSuccess(System.currentTimeMillis())
                container.syncNow() // upload anything answered before (re)connecting
                SetupState.Permissions
            } catch (e: AuthException) {
                SetupState.Scan(
                    error = "The server rejected this code. It may have been replaced by a newer one; " +
                        "generate a new code in the admin app.",
                )
            } catch (e: IOException) {
                SetupState.Confirm(link, error = "Couldn't reach ${link.serverHost}: ${e.message}")
            } catch (e: SerializationException) {
                SetupState.Confirm(link, error = "The server sent an unexpected response. Is this the right address?")
            }
        }
    }
}

@Composable
fun SetupScreen(container: AppContainer, onBack: (() -> Unit)?, onDone: () -> Unit) {
    val vm: SetupViewModel = viewModel { SetupViewModel(container) }
    val context = LocalContext.current
    val scanner = remember {
        GmsBarcodeScanning.getClient(
            context,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build(),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set up") },
                navigationIcon = { if (onBack != null) BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val state = vm.state) {
                is SetupState.Scan -> {
                    Text("Connect to your server", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "In the admin web app, open API key and choose Generate. Then scan the QR code it shows.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = {
                            scanner.startScan()
                                .addOnSuccessListener { barcode -> vm.onScanned(barcode.rawValue) }
                                .addOnFailureListener { e -> vm.onScanFailed("Couldn't start the scanner: ${e.message}") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Scan setup code") }
                }

                is SetupState.Confirm -> {
                    Text("Connect to this server?", style = MaterialTheme.typography.headlineSmall)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(state.link.serverHost, style = MaterialTheme.typography.titleMedium)
                            Text(state.link.serverUrl, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        "Your answers will be sent to this server. Only continue if it's yours.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { vm.connect(state.link) }) { Text("Connect") }
                        OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                }

                is SetupState.Connecting -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text("Connecting to ${state.link.serverHost}…")
                    }
                }

                SetupState.Permissions -> {
                    Text("Connected", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Last step: make sure reminders can reach you on time.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    ReminderChecklist(rememberReminderReadiness())
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
            }
        }
    }
}
