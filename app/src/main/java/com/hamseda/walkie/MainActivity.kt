package com.hamseda.walkie

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hamseda.walkie.data.SettingsRepository
import com.hamseda.walkie.service.VoiceService
import com.hamseda.walkie.session.SessionManager
import com.hamseda.walkie.ui.screens.DiagnosticsScreen
import com.hamseda.walkie.ui.screens.DiscoveryScreen
import com.hamseda.walkie.ui.screens.HomeScreen
import com.hamseda.walkie.ui.screens.PairingScreen
import com.hamseda.walkie.ui.screens.PrivacyScreen
import com.hamseda.walkie.ui.screens.SettingsScreen
import com.hamseda.walkie.ui.theme.HamSedaTheme
import com.hamseda.walkie.ui.vm.VmDeps
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val app get() = application as HamSedaApp
    private val managerState = mutableStateOf<SessionManager?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            managerState.value = (binder as VoiceService.LocalBinder).getManager()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            managerState.value = null
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // Apply the saved language before resources are loaded. Note: the
        // Application object exists here but its onCreate has not run yet,
        // so a standalone SettingsRepository is used (same DataStore file).
        // L9: runBlocking is unavoidable here (must run before super), but
        // bounded with a timeout so a stuck DataStore can't ANR startup.
        val code = try {
            runBlocking {
                kotlinx.coroutines.withTimeout(2000) {
                    SettingsRepository(newBase).languageFlow.first()
                }
            }
        } catch (e: Exception) {
            ""
        }
        if (code.isNotEmpty()) {
            val locale = Locale(code)
            Locale.setDefault(locale)
            val config = newBase.resources.configuration
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bindService(
            Intent(this, VoiceService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        setContent {
            val themeMode by app.settings.themeFlow
                .collectAsStateWithLifecycle(initialValue = SettingsRepository.ThemeMode.SYSTEM)
            val darkTheme = when (themeMode) {
                SettingsRepository.ThemeMode.LIGHT -> false
                SettingsRepository.ThemeMode.DARK -> true
                SettingsRepository.ThemeMode.SYSTEM ->
                    androidx.compose.foundation.isSystemInDarkTheme()
            }
            HamSedaTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val manager = managerState.value
                    if (manager == null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val deps = remember(manager) {
                            VmDeps(
                                appContext = applicationContext,
                                manager = manager,
                                transports = app.transportManager,
                                settings = app.settings,
                            )
                        }
                        HamSedaRoot(deps = deps, onLanguageChanged = { recreate() })
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            unbindService(connection)
        } catch (_: IllegalArgumentException) {}
        super.onDestroy()
    }
}

private enum class Screen { HOME, DISCOVERY, PAIRING, SETTINGS, PRIVACY, DIAGNOSTICS }

@Composable
private fun HamSedaRoot(deps: VmDeps, onLanguageChanged: () -> Unit) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    val phase by deps.manager.phase.collectAsStateWithLifecycle()

    // Genuine state-driven navigation: the SAS screen appears exactly while
    // the session waits for code comparison, and leaves when it resolves.
    LaunchedEffect(phase) {
        when (phase) {
            SessionManager.Phase.AWAITING_SAS_CONFIRM -> screen = Screen.PAIRING
            SessionManager.Phase.IN_SESSION ->
                if (screen == Screen.PAIRING) screen = Screen.HOME
            SessionManager.Phase.IDLE ->
                if (screen == Screen.PAIRING) screen = Screen.HOME
            else -> Unit
        }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            deps = deps,
            onOpenDiscovery = { screen = Screen.DISCOVERY },
            onOpenSettings = { screen = Screen.SETTINGS },
        )
        Screen.DISCOVERY -> DiscoveryScreen(
            deps = deps,
            onBack = { screen = Screen.HOME },
        )
        Screen.PAIRING -> PairingScreen(deps = deps)
        Screen.SETTINGS -> SettingsScreen(
            deps = deps,
            onBack = { screen = Screen.HOME },
            onOpenPrivacy = { screen = Screen.PRIVACY },
            onOpenDiagnostics = { screen = Screen.DIAGNOSTICS },
            onLanguageChanged = onLanguageChanged,
        )
        Screen.PRIVACY -> PrivacyScreen(onBack = { screen = Screen.SETTINGS })
        Screen.DIAGNOSTICS -> DiagnosticsScreen(onBack = { screen = Screen.SETTINGS })
    }
}
