package com.hamseda.walkie.transport

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the two transports and the user's [TransportPreference].
 *
 * - Exactly one transport is active at a time.
 * - AUTO_RECOMMEND picks Wi-Fi Direct when available (preferred voice
 *   transport), else Bluetooth, and reports the choice — never silently.
 * - Switching transports is only allowed while no session is active; the
 *   session layer enforces "never during active speech" and re-runs
 *   authentication on the new transport.
 */
class TransportManager(context: Context) {

    private val wifiDirect = WifiDirectTransport(context.applicationContext)
    private val bluetooth = BluetoothTransport(context.applicationContext)

    private val _preference = MutableStateFlow(TransportPreference.AUTO_RECOMMEND)
    val preference: StateFlow<TransportPreference> = _preference.asStateFlow()

    private val _activeType = MutableStateFlow(recommendedType())
    val activeType: StateFlow<TransportType> = _activeType.asStateFlow()

    /** Human-readable explanation of the current choice (shown in UI). */
    private val _choiceExplanation = MutableStateFlow("")
    val choiceExplanation: StateFlow<String> = _choiceExplanation.asStateFlow()

    val active: Transport
        get() = transportOf(_activeType.value)

    fun transportOf(type: TransportType): Transport =
        if (type == TransportType.WIFI_DIRECT) wifiDirect else bluetooth

    fun setPreference(pref: TransportPreference) {
        _preference.value = pref
        val next = when (pref) {
            TransportPreference.WIFI_DIRECT -> TransportType.WIFI_DIRECT
            TransportPreference.BLUETOOTH -> TransportType.BLUETOOTH
            TransportPreference.AUTO_RECOMMEND -> recommendedType()
        }
        _activeType.value = next
        _choiceExplanation.value = explainChoice(pref, next)
    }

    /** Re-evaluates AUTO_RECOMMEND (e.g. after a radio was toggled). */
    fun refreshRecommendation() {
        if (_preference.value == TransportPreference.AUTO_RECOMMEND) {
            val next = recommendedType()
            _activeType.value = next
            _choiceExplanation.value = explainChoice(_preference.value, next)
        }
    }

    private fun recommendedType(): TransportType {
        val wifi = wifiDirect.availability()
        val bt = bluetooth.availability()
        return recommendTransport(
            wifiSupported = wifi.supported,
            wifiEnabled = wifi.enabled,
            btSupported = bt.supported,
            btEnabled = bt.enabled,
        )
    }

    private fun explainChoice(pref: TransportPreference, type: TransportType): String =
        when (pref) {
            TransportPreference.AUTO_RECOMMEND -> if (type == TransportType.WIFI_DIRECT) {
                "auto_wifi_direct"
            } else {
                "auto_bluetooth"
            }
            TransportPreference.WIFI_DIRECT -> "manual_wifi_direct"
            TransportPreference.BLUETOOTH -> "manual_bluetooth"
        }

    fun close() {
        wifiDirect.close()
        bluetooth.close()
    }
}

/**
 * Pure transport-recommendation decision table, extracted from
 * [TransportManager] so the matrix is unit-testable without Android radios.
 *
 * Policy: Wi-Fi Direct wins when its radio is on; a usable Bluetooth radio
 * outranks a Wi-Fi Direct radio the user would have to enable manually;
 * otherwise prefer Wi-Fi Direct when the hardware exists at all.
 */
fun recommendTransport(
    wifiSupported: Boolean,
    wifiEnabled: Boolean,
    btSupported: Boolean,
    btEnabled: Boolean,
): TransportType {
    if (wifiSupported && wifiEnabled) return TransportType.WIFI_DIRECT
    // Wi-Fi Direct present but radio off still outranks Bluetooth only
    // when the user can enable it; otherwise fall through.
    if (btSupported && btEnabled) return TransportType.BLUETOOTH
    if (wifiSupported) return TransportType.WIFI_DIRECT
    return TransportType.BLUETOOTH
}
