package com.hamseda.walkie

import android.app.Application
import com.hamseda.walkie.data.SettingsRepository
import com.hamseda.walkie.transport.TransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Application singletons: transport ownership and persisted settings. */
class HamSedaApp : Application() {
    lateinit var transportManager: TransportManager
        private set
    lateinit var settings: SettingsRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        transportManager = TransportManager(this)
        // L9: no runBlocking on the main thread. The preference is applied
        // asynchronously; TransportManager starts with a safe default.
        appScope.launch {
            try {
                transportManager.setPreference(settings.transportPreference())
            } catch (e: Exception) {
                android.util.Log.w("HamSedaApp", "pref load failed", e)
            }
        }
    }
}
