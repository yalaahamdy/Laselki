package com.wavetalk.app.settings

/** User-controlled application settings, persisted locally via DataStore. */
data class AppSettings(
    /** Display name broadcast to other devices. Null until first initialization. */
    val deviceName: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val vibrationEnabled: Boolean = true,
    val soundEffectsEnabled: Boolean = true,
    val backgroundReadyEnabled: Boolean = true,

    /** Microphone software gain, 0.5f .. 2.0f (50% .. 200%). */
    val micGain: Float = 1.0f,

    /** Last used channel, restored on launch. */
    val lastChannel: String = "Lobby",
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }
