package com.meatsuitdiagnostics.app

import android.app.Application
import com.meatsuitdiagnostics.app.sync.SyncScheduler

class MeatSuitApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.ensureChannel()
        SyncScheduler.schedulePeriodic(this)
    }
}
