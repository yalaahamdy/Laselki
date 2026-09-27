package com.wavetalk.app.domain

import java.util.UUID

/** Runtime status of a remote device as seen by the presence tracker. */
enum class DeviceStatus { ONLINE, OFFLINE }

/** What a remote device is currently doing with its microphone. */
enum class RemoteTalkState { IDLE, TALKING }

/**
 * A remote WaveTalk device discovered on the local network.
 */
data class Device(
    val id: UUID,
    val name: String,
    val host: String,
    val audioPort: Int,
    val channel: String,
    val appVersion: Int,
    val status: DeviceStatus,
    val talkState: RemoteTalkState,
    val lastSeenMs: Long,
)

/** Scope of a push-to-talk transmission. */
sealed interface TalkScope {
    /** Transmit to every online device currently in the given channel. */
    data object Channel : TalkScope

    /** Transmit to one specific device only. */
    data class Single(val device: Device) : TalkScope
}

/** Local microphone / transmission phase of this device. */
sealed interface TalkPhase {
    data object Idle : TalkPhase

    /** Transmitting right now. */
    data object Talking : TalkPhase

    /** Press blocked because another device is talking. */
    data class Busy(val talkerName: String) : TalkPhase
}

/** Wi-Fi connectivity state of this device. */
data class NetworkState(
    val wifiConnected: Boolean = false,
    val localAddress: String? = null,
)

/** Overall UI-facing engine state, aggregated by TalkEngine. */
data class EngineState(
    val network: NetworkState = NetworkState(),
    val devices: List<Device> = emptyList(),
    val channel: String = "",
    val myName: String = "",
    val scope: TalkScope = TalkScope.Channel,
    val phase: TalkPhase = TalkPhase.Idle,
    val receivingFrom: String? = null,
    val audioLevel: Float = 0f,
    val engineError: Boolean = false,
) {
    val onlineDevices: List<Device> get() = devices.filter { it.status == DeviceStatus.ONLINE }
    val onlineCount: Int get() = onlineDevices.size

    /** Name of a remote talker currently audible in our channel, if any. */
    val remoteTalker: Device?
        get() = devices.firstOrNull {
            it.status == DeviceStatus.ONLINE && it.talkState == RemoteTalkState.TALKING
        }
}
