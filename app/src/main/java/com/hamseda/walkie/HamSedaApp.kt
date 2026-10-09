package com.hamseda.walkie

import android.app.Application
import com.hamseda.walkie.data.SettingsRepository
import com.hamseda.walkie.transport.TransportManager

/** Application singletons: transport ownership and persisted settings. */
class HamSedaApp : Application() {
    lateinit var transportManager: TransportManager
        private set
    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        transportManager = TransportManager(this)
        transportManager.setPreference(
            kotlinx.coroutines.runBlocking { settings.transportPreference() },
        )
    }
}
