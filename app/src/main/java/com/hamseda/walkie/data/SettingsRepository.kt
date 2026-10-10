package com.hamseda.walkie.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hamseda.walkie.proto.CodecId
import com.hamseda.walkie.transport.TransportPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Maps persisted or caller-supplied codec IDs to one of the supported codecs. */
internal fun normalizeCodecPreference(value: Int?): Byte = when (value) {
    CodecId.OPUS.toInt() -> CodecId.OPUS
    CodecId.PCM16.toInt() -> CodecId.PCM16
    else -> CodecId.OPUS
}

/** A corrupted or obsolete persisted locale must not reach Locale construction. */
internal fun normalizeLanguageCode(value: String?): String = when (value) {
    "fa" -> "fa"
    "en" -> "en"
    else -> ""
}

private val Context.dataStore by preferencesDataStore("hamseda_settings")

/**
 * Persisted settings. Only non-sensitive UI preferences are stored —
 * never keys, audio, identities, or contact lists (there are none).
 */
class SettingsRepository(private val context: Context) {

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    private object Keys {
        val TRANSPORT = stringPreferencesKey("transport_preference")
        val CODEC = intPreferencesKey("codec_pref")
        val SPEAKERPHONE = booleanPreferencesKey("speakerphone")
        val VIBRATION = booleanPreferencesKey("vibration")
        val THEME = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language")
    }

    val transportPreferenceFlow: Flow<TransportPreference> =
        context.dataStore.data.map {
            runCatching { TransportPreference.valueOf(it[Keys.TRANSPORT] ?: "") }
                .getOrDefault(TransportPreference.AUTO_RECOMMEND)
        }

    val codecPrefFlow: Flow<Byte> =
        context.dataStore.data.map { normalizeCodecPreference(it[Keys.CODEC]) }

    val speakerphoneFlow: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.SPEAKERPHONE] ?: true }

    val vibrationFlow: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.VIBRATION] ?: true }

    val themeFlow: Flow<ThemeMode> =
        context.dataStore.data.map {
            runCatching { ThemeMode.valueOf(it[Keys.THEME] ?: "") }
                .getOrDefault(ThemeMode.SYSTEM)
        }

    /** "fa", "en", or "" for system default. */
    val languageFlow: Flow<String> =
        context.dataStore.data.map { normalizeLanguageCode(it[Keys.LANGUAGE]) }

    suspend fun transportPreference(): TransportPreference = transportPreferenceFlow.first()
    suspend fun codecPref(): Byte = codecPrefFlow.first()

    suspend fun setTransportPreference(v: TransportPreference) {
        context.dataStore.edit { it[Keys.TRANSPORT] = v.name }
    }

    suspend fun setCodecPref(v: Byte) {
        context.dataStore.edit { it[Keys.CODEC] = normalizeCodecPreference(v.toInt()).toInt() }
    }

    suspend fun setSpeakerphone(v: Boolean) {
        context.dataStore.edit { it[Keys.SPEAKERPHONE] = v }
    }

    suspend fun setVibration(v: Boolean) {
        context.dataStore.edit { it[Keys.VIBRATION] = v }
    }

    suspend fun setTheme(v: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME] = v.name }
    }

    /** L10: only validated language codes are persisted. */
    suspend fun setLanguage(v: String) {
        context.dataStore.edit { it[Keys.LANGUAGE] = normalizeLanguageCode(v) }
    }
}
