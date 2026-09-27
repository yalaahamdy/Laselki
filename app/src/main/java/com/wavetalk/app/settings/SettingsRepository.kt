package com.wavetalk.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wavetalk_settings")

/**
 * Local, private settings persistence. Nothing here ever leaves the device.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val THEME = intPreferencesKey("theme_mode")
        val VIBRATION = booleanPreferencesKey("vibration")
        val SOUNDS = booleanPreferencesKey("sounds")
        val BACKGROUND = booleanPreferencesKey("background_ready")
        val MIC_GAIN = floatPreferencesKey("mic_gain")
        val LAST_CHANNEL = stringPreferencesKey("last_channel")
        val ONBOARDED = booleanPreferencesKey("onboarded")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            deviceName = p[Keys.DEVICE_NAME],
            themeMode = ThemeMode.entries.getOrElse(p[Keys.THEME] ?: 0) { ThemeMode.SYSTEM },
            vibrationEnabled = p[Keys.VIBRATION] ?: true,
            soundEffectsEnabled = p[Keys.SOUNDS] ?: true,
            backgroundReadyEnabled = p[Keys.BACKGROUND] ?: true,
            micGain = (p[Keys.MIC_GAIN] ?: 1.0f).coerceIn(MIN_GAIN, MAX_GAIN),
            lastChannel = p[Keys.LAST_CHANNEL]?.takeIf { it.isNotBlank() } ?: DEFAULT_CHANNEL,
        )
    }

    val onboarded: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDED] ?: false }

    /**
     * Guarantees stable identity: a persistent random UUID and a friendly default
     * display name. Called once at engine start; safe to call repeatedly.
     */
    suspend fun ensureIdentity(defaultNameSuffix: () -> String = { UUID.randomUUID().toString().take(4).uppercase() }) {
        context.dataStore.edit { p ->
            if (p[Keys.DEVICE_ID] == null) p[Keys.DEVICE_ID] = UUID.randomUUID().toString()
            if (p[Keys.DEVICE_NAME] == null) p[Keys.DEVICE_NAME] = "Wave-${defaultNameSuffix()}"
        }
    }

    suspend fun deviceId(): String =
        context.dataStore.data.first()[Keys.DEVICE_ID] ?: UUID.randomUUID().toString()

    suspend fun setDeviceName(name: String) {
        context.dataStore.edit { it[Keys.DEVICE_NAME] = name.trim().take(MAX_NAME_LENGTH) }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME] = mode.ordinal }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VIBRATION] = enabled }
    }

    suspend fun setSoundEffectsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SOUNDS] = enabled }
    }

    suspend fun setBackgroundReadyEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BACKGROUND] = enabled }
    }

    suspend fun setMicGain(gain: Float) {
        context.dataStore.edit { it[Keys.MIC_GAIN] = gain.coerceIn(MIN_GAIN, MAX_GAIN) }
    }

    suspend fun setLastChannel(channel: String) {
        context.dataStore.edit { it[Keys.LAST_CHANNEL] = channel.trim().take(MAX_CHANNEL_LENGTH) }
    }

    suspend fun setOnboarded() {
        context.dataStore.edit { it[Keys.ONBOARDED] = true }
    }

    companion object {
        const val MAX_NAME_LENGTH = 24
        const val MAX_CHANNEL_LENGTH = 24
        const val DEFAULT_CHANNEL = "Lobby"
        const val MIN_GAIN = 0.5f
        const val MAX_GAIN = 2.0f
    }
}
