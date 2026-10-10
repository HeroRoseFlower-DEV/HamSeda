package com.hamseda.walkie.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hamseda.walkie.data.SettingsRepository
import com.hamseda.walkie.proto.CodecId
import com.hamseda.walkie.service.VoiceService
import com.hamseda.walkie.session.SessionManager
import com.hamseda.walkie.transport.PeerDevice
import com.hamseda.walkie.transport.Transport
import com.hamseda.walkie.transport.TransportManager
import com.hamseda.walkie.transport.TransportPreference
import com.hamseda.walkie.transport.TransportState
import com.hamseda.walkie.transport.TransportType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Dependencies every screen ViewModel needs. */
data class VmDeps(
    val appContext: Context,
    val manager: SessionManager,
    val transports: TransportManager,
    val settings: SettingsRepository,
)

class HomeViewModel(private val deps: VmDeps) : ViewModel() {
    val phase = deps.manager.phase
    val peerName = deps.manager.peerName
    val transportType = deps.manager.transportType
    val isTransmitting = deps.manager.isTransmitting
    val floorHolder = deps.manager.floorHolder
    val voiceLevel = deps.manager.voiceLevel
    val error = deps.manager.error

    fun setPtt(pressed: Boolean) = deps.manager.setPttPressed(pressed)
    fun endSession() {
        deps.manager.endSession()
        VoiceService.stop(deps.appContext)
    }
    fun clearError() = deps.manager.clearError()
}

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryViewModel(private val deps: VmDeps) : ViewModel() {
    val preference = deps.transports.preference
    val activeType = deps.transports.activeType
    val choiceExplanation = deps.transports.choiceExplanation
    val phase = deps.manager.phase

    val peers: StateFlow<List<PeerDevice>> =
        deps.transports.activeType.flatMapLatest { type ->
            deps.transports.transportOf(type).peers
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val transportState: StateFlow<TransportState> =
        deps.transports.activeType.flatMapLatest { type ->
            deps.transports.transportOf(type).state
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransportState.IDLE)

    val transportError =
        deps.transports.activeType.flatMapLatest { type ->
            deps.transports.transportOf(type).error
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True when this phone can currently be found by the peer's scan. */
    val discoverable: StateFlow<Boolean> =
        deps.transports.activeType.flatMapLatest { type ->
            deps.transports.transportOf(type).discoverable
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun clearTransportError() {
        deps.transports.active.clearError()
    }

    /**
     * Brings any lingering session back to IDLE first. Without this, tapping
     * "connect" while a previous listen/connect is still winding down hits
     * SessionManager's `if (phase != IDLE) return` guard and *silently does
     * nothing* — the button looks dead.
     */
    private suspend fun restartIdle(): Boolean {
        if (deps.manager.phase.value == SessionManager.Phase.IDLE) return true
        deps.manager.endSession()
        return try {
            withTimeout(5_000) {
                deps.manager.phase.first { it == SessionManager.Phase.IDLE }
            }
            true
        } catch (_: TimeoutCancellationException) {
            android.widget.Toast.makeText(
                deps.appContext,
                deps.appContext.getString(com.hamseda.walkie.R.string.err_restart_timeout),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            false
        }
    }

    // L1: manual refresh trigger — the availability flow below only
    // recomputes when activeType changes, so Retry must nudge it too.
    private val availabilityRefresh = MutableStateFlow(0)

    val availability: StateFlow<com.hamseda.walkie.transport.Availability?> =
        combine(deps.transports.activeType, availabilityRefresh) { type, _ -> type }
            .flatMapLatest { type ->
                flowOf(deps.transports.transportOf(type).availability())
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setPreference(p: TransportPreference) {
        viewModelScope.launch {
            if (deps.manager.phase.value != SessionManager.Phase.IDLE) return@launch
            // Switch synchronously before the first suspension so a session
            // started concurrently always captures the selected transport.
            deps.transports.setPreference(p)
            deps.settings.setTransportPreference(p)
        }
    }

    fun startScan() {
        viewModelScope.launch { activeTransport().startDiscovery() }
    }

    fun stopScan() {
        viewModelScope.launch { activeTransport().stopDiscovery() }
    }

    /** Outgoing secure session to [peer]. Starts the foreground service first. */
    fun connectPeer(peer: PeerDevice) {
        viewModelScope.launch {
            if (!restartIdle()) return@launch
            VoiceService.start(deps.appContext)
            val codec = deps.settings.codecPref()
            deps.manager.startOutgoing(activeTransport(), peer, codec)
        }
    }

    /** Listen for an incoming connection on the active transport. */
    fun listenForIncoming() {
        viewModelScope.launch {
            restartIdle()
            VoiceService.start(deps.appContext)
            val codec = deps.settings.codecPref()
            deps.manager.acceptIncoming(activeTransport(), codec)
        }
    }

    /** Stops waiting/connecting and returns everything to idle. */
    fun cancelSession() {
        deps.manager.endSession()
        VoiceService.stop(deps.appContext)
    }

    fun refreshAvailability() {
        // Retrying availability must not change the selected radio beneath an
        // active session; only recompute AUTO_RECOMMEND while idle.
        if (deps.manager.phase.value == SessionManager.Phase.IDLE) {
            deps.transports.refreshRecommendation()
        }
        availabilityRefresh.value += 1
    }

    private fun activeTransport(): Transport = deps.transports.active
}

class PairingViewModel(private val deps: VmDeps) : ViewModel() {
    val sasCode = deps.manager.sasCode
    val peerConfirmed = deps.manager.peerSasConfirmed
    val phase = deps.manager.phase

    fun confirm(match: Boolean) = deps.manager.confirmSas(match)
    fun cancel() {
        deps.manager.endSession()
        VoiceService.stop(deps.appContext)
    }
}

class SettingsViewModel(private val deps: VmDeps) : ViewModel() {
    val transportPreference = deps.settings.transportPreferenceFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransportPreference.AUTO_RECOMMEND)
    val codecPref = deps.settings.codecPrefFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CodecId.OPUS)
    val speakerphone = deps.settings.speakerphoneFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val vibration = deps.settings.vibrationFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val theme = deps.settings.themeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.ThemeMode.SYSTEM)
    val language = deps.settings.languageFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val phase = deps.manager.phase

    fun setTransportPreference(p: TransportPreference) {
        viewModelScope.launch {
            if (deps.manager.phase.value != SessionManager.Phase.IDLE) return@launch
            // Transport changes are immediate; persist after selection so the
            // currently active session cannot be silently moved to another radio.
            deps.transports.setPreference(p)
            deps.settings.setTransportPreference(p)
        }
    }

    fun setCodec(codecId: Byte) {
        viewModelScope.launch { deps.settings.setCodecPref(codecId) }
    }

    fun setSpeakerphone(on: Boolean) {
        viewModelScope.launch { deps.settings.setSpeakerphone(on) }
    }

    fun setVibration(on: Boolean) {
        viewModelScope.launch { deps.settings.setVibration(on) }
    }

    fun setTheme(mode: SettingsRepository.ThemeMode) {
        viewModelScope.launch { deps.settings.setTheme(mode) }
    }

    fun setLanguage(code: String, onChanged: () -> Unit) {
        viewModelScope.launch {
            deps.settings.setLanguage(code)
            onChanged()
        }
    }
}

@Suppress("UNCHECKED_CAST")
class VmFactory(private val deps: VmDeps) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(deps)
        modelClass.isAssignableFrom(DiscoveryViewModel::class.java) -> DiscoveryViewModel(deps)
        modelClass.isAssignableFrom(PairingViewModel::class.java) -> PairingViewModel(deps)
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(deps)
        else -> throw IllegalArgumentException("unknown VM ${modelClass.name}")
    } as T
}
