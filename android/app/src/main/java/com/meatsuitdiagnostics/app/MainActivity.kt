package com.meatsuitdiagnostics.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.meatsuitdiagnostics.app.domain.SetupLink
import com.meatsuitdiagnostics.app.ui.AppLink
import com.meatsuitdiagnostics.app.ui.AppNavHost
import com.meatsuitdiagnostics.app.ui.theme.MeatSuitTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as MeatSuitApp).container }

    /** A link from the launching intent (notification tap or setup deep link) for the UI to act on once. */
    private val links = MutableStateFlow<AppLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MeatSuitTheme {
                AppNavHost(container = container, links = links, onLinkHandled = { links.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Alarms can be late or lost; whenever the app is opened, close anything whose time is up.
        lifecycleScope.launch { container.checkins.expireDue() }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_CHECKIN -> intent.getStringExtra(EXTRA_INSTANCE_ID)?.let { links.value = AppLink.OpenCheckin(it) }
            Intent.ACTION_VIEW -> {
                // Never applied directly: the setup screen shows the server and asks before connecting.
                container.pendingSetupLink.value = intent.dataString?.let(SetupLink::parse)
                links.value = AppLink.Setup
            }
        }
    }

    companion object {
        private const val ACTION_OPEN_CHECKIN = "com.meatsuitdiagnostics.app.action.OPEN_CHECKIN"
        private const val EXTRA_INSTANCE_ID = "instance_id"

        fun openCheckinIntent(context: Context, instanceId: String): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_CHECKIN)
                .setData(Uri.parse("meatsuit-app://checkin/$instanceId"))
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
    }
}
