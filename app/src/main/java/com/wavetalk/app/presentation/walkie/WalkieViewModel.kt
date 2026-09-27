package com.wavetalk.app.presentation.walkie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wavetalk.app.di.AppContainer
import com.wavetalk.app.domain.Device
import com.wavetalk.app.domain.EngineState
import com.wavetalk.app.domain.TalkScope
import com.wavetalk.app.session.TalkEngine
import com.wavetalk.app.settings.AppSettings
import com.wavetalk.app.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Bridges the engine's state flows to the Compose UI and performs actions.
 */
class WalkieViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val engine: TalkEngine = container.talkEngine

    val state: StateFlow<EngineState> = engine.state

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val events = engine.events

    fun press(micGranted: Boolean) {
        viewModelScope.launch { engine.press(micGranted) }
    }

    fun release() = engine.release()

    fun setChannel(channel: String) = engine.setChannel(channel)

    fun talkToDevice(device: Device) = engine.setScope(TalkScope.Single(device))

    fun clearDeviceScope() = engine.clearScope()

    fun knownChannelsSnapshot(): List<Pair<String, Int>> = engine.knownChannels()

    fun saveDeviceName(name: String) {
        viewModelScope.launch { container.settingsRepository.setDeviceName(name) }
    }

    fun setVibration(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setVibrationEnabled(enabled) }
    }

    fun setSounds(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setSoundEffectsEnabled(enabled) }
    }

    fun setBackgroundReady(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setBackgroundReadyEnabled(enabled) }
    }

    fun setMicGain(gain: Float) {
        viewModelScope.launch { container.settingsRepository.setMicGain(gain) }
    }

    fun setThemeMode(mode: com.wavetalk.app.settings.ThemeMode) {
        viewModelScope.launch { container.settingsRepository.setThemeMode(mode) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = WalkieViewModel(container) as T
    }
}
