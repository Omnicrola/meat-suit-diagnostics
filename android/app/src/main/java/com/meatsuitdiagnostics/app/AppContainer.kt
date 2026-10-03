package com.meatsuitdiagnostics.app

import android.content.Context
import androidx.room.Room
import com.meatsuitdiagnostics.app.data.CheckinRepository
import com.meatsuitdiagnostics.app.data.ConfigRepository
import com.meatsuitdiagnostics.app.data.Syncer
import com.meatsuitdiagnostics.app.data.api.ApiClient
import com.meatsuitdiagnostics.app.data.db.AppDatabase
import com.meatsuitdiagnostics.app.data.settings.KeystoreCipher
import com.meatsuitdiagnostics.app.data.settings.SettingsStore
import com.meatsuitdiagnostics.app.domain.SetupLink
import com.meatsuitdiagnostics.app.scheduling.AlarmHandler
import com.meatsuitdiagnostics.app.scheduling.AlarmScheduler
import com.meatsuitdiagnostics.app.scheduling.Notifier
import com.meatsuitdiagnostics.app.sync.SyncScheduler
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** Creates and holds the app's long-lived objects. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** For work that must outlive a screen, such as handling an alarm. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = Json { ignoreUnknownKeys = true }

    private val db = Room.databaseBuilder(appContext, AppDatabase::class.java, "meatsuit.db").build()

    val settings = SettingsStore(appContext, KeystoreCipher())

    val api = ApiClient(
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build(),
        json,
    )

    val scheduler = AlarmScheduler(appContext)
    val notifier = Notifier(appContext)
    val config = ConfigRepository(db.configDao(), scheduler)
    val checkins = CheckinRepository(db, scheduler, notifier, json, requestSync = { SyncScheduler.syncNow(appContext) })
    val syncer = Syncer(settings, api, config, db.outboxDao(), json)
    val alarmHandler = AlarmHandler(checkins, db.configDao(), scheduler, notifier)

    /** A setup link from a scanned QR code or deep link, waiting for the user to confirm it. */
    val pendingSetupLink = MutableStateFlow<SetupLink?>(null)

    fun syncNow() = SyncScheduler.syncNow(appContext)
}
