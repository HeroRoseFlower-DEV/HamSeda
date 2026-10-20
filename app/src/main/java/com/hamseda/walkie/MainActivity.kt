package com.hamseda.walkie

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hamseda.walkie.data.SettingsRepository
import com.hamseda.walkie.data.normalizeLanguageCode
import com.hamseda.walkie.messaging.MessagingEngine
import com.hamseda.walkie.service.VoiceService
import com.hamseda.walkie.session.SessionManager
import com.hamseda.walkie.ui.screens.ChatScreen
import com.hamseda.walkie.ui.screens.DiagnosticsScreen
import com.hamseda.walkie.ui.screens.DiscoveryScreen
import com.hamseda.walkie.ui.screens.HomeScreen
import com.hamseda.walkie.ui.screens.MessagesScreen
import com.hamseda.walkie.ui.screens.PairingScreen
import com.hamseda.walkie.ui.screens.PrivacyScreen
import com.hamseda.walkie.ui.screens.ProfileScreen
import com.hamseda.walkie.ui.screens.SettingsScreen
import com.hamseda.walkie.ui.theme.HamSedaTheme
import com.hamseda.walkie.ui.theme.TelegramColors
import com.hamseda.walkie.ui.screens.StartupPermissionGate
import com.hamseda.walkie.ui.vm.VmDeps
import com.hamseda.walkie.util.toLocalizedDigits
import kotlinx.coroutines.runBlocking
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val app get() = application as HamSedaApp
    private val managerState = mutableStateOf<SessionManager?>(null)
    private val messagingState = mutableStateOf<MessagingEngine?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as VoiceService.LocalBinder
            managerState.value = local.getManager()
            messagingState.value = local.getMessaging()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            managerState.value = null
            messagingState.value = null
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // Apply the saved language before resources are loaded. Note: the
        // Application object exists here but its onCreate has not run yet,
        // so a standalone SettingsRepository is used (same DataStore file).
        // L9: runBlocking is unavoidable here (must run before super), but
        // bounded with a timeout so a stuck DataStore can't ANR startup.
        // Fresh installs default to Persian (HamSeda is a Persian-first
        // app) and the choice is persisted so the settings UI reflects it.
        // An explicit user choice (fa / en / System) is always respected,
        // and a failed read never overwrites anything (falls back to
        // system for that launch only).
        val repo = SettingsRepository(newBase)
        val read: Result<String?> = runCatching {
            runBlocking {
                kotlinx.coroutines.withTimeout(2000) { repo.rawLanguageCode() }
            }
        }
        val code = when {
            read.isFailure -> ""
            read.getOrNull() == null -> {
                runCatching {
                    runBlocking {
                        kotlinx.coroutines.withTimeout(2000) { repo.setLanguage("fa") }
                    }
                }
                "fa"
            }
            else -> normalizeLanguageCode(read.getOrNull())
        }
        if (code.isNotEmpty()) {
            val locale = Locale(code)
            Locale.setDefault(locale)
            val config = newBase.resources.configuration
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            // System language: undo any Locale.setDefault() an earlier
            // explicit-language attach performed in this same process
            // (round-7 L12). Resources already follow the system
            // configuration here, but without this the process default
            // would stay on the previously chosen language until death
            // for anything that reads Locale.getDefault().
            val systemLocale = newBase.resources.configuration.locales[0]
            Locale.setDefault(systemLocale)
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
                    StartupPermissionGate {
                        val manager = managerState.value
                        val messaging = messagingState.value
                        if (manager == null || messaging == null) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        } else {
                            val deps = remember(manager, messaging) {
                                VmDeps(
                                    appContext = applicationContext,
                                    manager = manager,
                                    transports = app.transportManager,
                                    settings = app.settings,
                                    messaging = messaging,
                                )
                            }
                            HamSedaRoot(deps = deps, darkTheme = darkTheme, onLanguageChanged = { recreate() })
                        }
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

private enum class Screen {
    HOME, DISCOVERY, PAIRING, SETTINGS, PRIVACY, DIAGNOSTICS,
    MESSAGES, CHAT, PROFILE,
}

@Composable
private fun HamSedaRoot(deps: VmDeps, darkTheme: Boolean, onLanguageChanged: () -> Unit) {
    // Saveable: rotating the phone on the Settings screen must not kick the
    // user back to Home.
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var chatPeerId by rememberSaveable { mutableStateOf<String?>(null) }
    val phase by deps.manager.phase.collectAsStateWithLifecycle()
    val sasCode by deps.manager.sasCode.collectAsStateWithLifecycle()
    val peerIdentityTrusted by deps.manager.peerIdentityTrusted.collectAsStateWithLifecycle()
    val unreadTotal by deps.messaging.unreadTotal.collectAsStateWithLifecycle()

    // Consume system Back for Compose's state-driven navigation so a secondary
    // screen does not finish the Activity. Keep SAS confirmation visible.
    BackHandler(enabled = screen != Screen.HOME) {
        when (screen) {
            Screen.DISCOVERY, Screen.SETTINGS, Screen.MESSAGES -> screen = Screen.HOME
            Screen.PRIVACY, Screen.DIAGNOSTICS, Screen.PROFILE -> screen = Screen.SETTINGS
            Screen.CHAT -> screen = Screen.MESSAGES
            Screen.PAIRING -> Unit
            Screen.HOME -> Unit
        }
    }

    // Genuine state-driven navigation: the SAS screen appears exactly while
    // the session waits for code comparison, and leaves when it resolves.
    // Messaging screens are never yanked away: a live chat survives session
    // changes (messages queue in the outbox).
    //
    // inChat is a key (not just read inside): if AWAITING_SAS_CONFIRM
    // arrives while the user is in chat, navigation is correctly deferred —
    // but the effect must re-fire when the user LEAVES the chat, otherwise
    // the PAIRING screen is never shown and the session strands. Keying on
    // the boolean (rather than screen) avoids re-firing on every unrelated
    // navigation, e.g. opening Settings during IN_SESSION must not bounce
    // back to HOME.
    val inChat = screen == Screen.MESSAGES || screen == Screen.CHAT
    LaunchedEffect(phase, sasCode, peerIdentityTrusted, inChat) {
        when (phase) {
            SessionManager.Phase.AWAITING_SAS_CONFIRM -> {
                if (sasCode != null && !inChat) screen = Screen.PAIRING
                else if (peerIdentityTrusted && !inChat) screen = Screen.HOME
            }
            SessionManager.Phase.IN_SESSION ->
                if (!inChat && screen != Screen.HOME) screen = Screen.HOME
            SessionManager.Phase.IDLE ->
                if (screen == Screen.PAIRING) screen = Screen.HOME
            else -> Unit
        }
    }

    Scaffold(
        bottomBar = {
            if (screen == Screen.HOME || screen == Screen.MESSAGES) {
                MainBottomBar(
                    screen = screen,
                    unreadTotal = unreadTotal,
                    dark = darkTheme,
                    onSelect = { screen = it },
                )
            }
        },
    ) { padding ->
        // ChatScreen owns its bottom inset entirely (max of IME / nav bars):
        // applying the root padding's bottom here too would stack a second
        // nav-bar inset under the chat's own (nested-Scaffold double inset).
        // absolutePadding with the absolute values is identical to
        // padding(padding) for top/sides; only the bottom differs.
        val layoutDir = LocalLayoutDirection.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .absolutePadding(
                    left = padding.calculateLeftPadding(layoutDir),
                    top = padding.calculateTopPadding(),
                    right = padding.calculateRightPadding(layoutDir),
                    bottom = if (screen == Screen.CHAT) 0.dp else padding.calculateBottomPadding(),
                ),
        ) {
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
                    onOpenProfile = { screen = Screen.PROFILE },
                    onOpenPrivacy = { screen = Screen.PRIVACY },
                    onOpenDiagnostics = { screen = Screen.DIAGNOSTICS },
                    onLanguageChanged = onLanguageChanged,
                )
                Screen.PROFILE -> {
                    // Refresh the Settings card photo when coming back.
                    ProfileScreen(
                        deps = deps,
                        dark = darkTheme,
                        onBack = { screen = Screen.SETTINGS },
                    )
                }
                Screen.PRIVACY -> PrivacyScreen(onBack = { screen = Screen.SETTINGS })
                Screen.DIAGNOSTICS -> DiagnosticsScreen(onBack = { screen = Screen.SETTINGS })
                Screen.MESSAGES -> MessagesScreen(
                    deps = deps,
                    dark = darkTheme,
                    onOpenChat = { peerId ->
                        chatPeerId = peerId
                        screen = Screen.CHAT
                    },
                    onOpenDiscovery = { screen = Screen.DISCOVERY },
                )
                Screen.CHAT -> {
                    val peerId = chatPeerId
                    if (peerId != null) {
                        // Keyed by peer: switching conversations must fully
                        // reset ChatScreen's remembered state — above all
                        // the input draft, which must never leak from one
                        // conversation into another.
                        key(peerId) {
                            ChatScreen(
                                deps = deps,
                                peerId = peerId,
                                dark = darkTheme,
                                onBack = { screen = Screen.MESSAGES },
                            )
                        }
                    } else {
                        // Defer: assigning state during composition is a side effect.
                        LaunchedEffect(Unit) { screen = Screen.MESSAGES }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainBottomBar(
    screen: Screen,
    unreadTotal: Int,
    dark: Boolean,
    onSelect: (Screen) -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = screen == Screen.HOME,
            onClick = { onSelect(Screen.HOME) },
            icon = {
                Icon(Icons.Filled.Mic, contentDescription = null)
            },
            label = { Text(stringResource(R.string.tab_walkie)) },
        )
        NavigationBarItem(
            selected = screen == Screen.MESSAGES,
            onClick = { onSelect(Screen.MESSAGES) },
            icon = {
                BadgedBox(
                    badge = {
                        if (unreadTotal > 0) {
                            Badge(
                                containerColor = if (dark) TelegramColors.UnreadBadgeDark
                                else TelegramColors.UnreadBadgeLight,
                            ) {
                                Text(
                                    unreadTotal.toString().toLocalizedDigits(
                                        androidx.compose.ui.text.intl.Locale.current.platformLocale,
                                    ),
                                )
                            }
                        }
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                }
            },
            label = { Text(stringResource(R.string.tab_messages)) },
        )
    }
}
