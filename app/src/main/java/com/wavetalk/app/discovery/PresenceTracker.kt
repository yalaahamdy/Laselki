package com.wavetalk.app.discovery

import com.wavetalk.app.core.AppConstants
import com.wavetalk.app.domain.Device
import com.wavetalk.app.domain.DeviceStatus
import com.wavetalk.app.domain.RemoteTalkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which WaveTalk devices are present on the network, combining periodic
 * announcements (heartbeat) with immediate talk-state events.
 *
 * Time is injectable so the tracker is fully unit-testable with a virtual clock.
 */
class PresenceTracker(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val offlineAfterMs: Long = AppConstants.PRESENCE_OFFLINE_MS,
    private val evictAfterMs: Long = AppConstants.PRESENCE_EVICT_MS,
) {

    private class Entry(
        var device: Device,
        var talking: Boolean,
        var lastSeenMs: Long,
        val provisional: Boolean,
    )

    private val entries = ConcurrentHashMap<UUID, Entry>()

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    /** Applies a datagram received from [host]. Self-packets must be filtered before calling. */
    fun onPacket(host: InetAddress, packet: DiscoveryPacket) {
        val now = nowMs()
        when (packet.type) {
            DiscoveryType.ANNOUNCE -> upsert(host, packet, now, provisional = false)
            DiscoveryType.TALK_START -> {
                val entry = upsert(host, packet, now, provisional = true)
                entry.talking = true
            }
            DiscoveryType.TALK_STOP -> {
                val entry = upsert(host, packet, now, provisional = true)
                entry.talking = false
            }
            DiscoveryType.LEAVE -> entries.remove(packet.deviceId)
        }
        publish()
    }

    private fun upsert(host: InetAddress, packet: DiscoveryPacket, now: Long, provisional: Boolean): Entry {
        val existing = entries[packet.deviceId]
        if (existing != null) {
            existing.lastSeenMs = now
            if (packet.type == DiscoveryType.ANNOUNCE) {
                // Refresh live identity fields from the freshest announcement.
                existing.device = existing.device.copy(
                    name = packet.name.ifBlank { existing.device.name },
                    host = host.hostAddress ?: existing.device.host,
                    audioPort = packet.audioPort,
                    channel = packet.channel,
                )
                existing.talking = packet.talking
            }
            return existing
        }
        val device = Device(
            id = packet.deviceId,
            name = packet.name.ifBlank { "Wave device" },
            host = host.hostAddress ?: "unknown",
            audioPort = packet.audioPort,
            channel = packet.channel,
            appVersion = packet.appVersion,
            status = DeviceStatus.ONLINE,
            talkState = if (packet.talking) RemoteTalkState.TALKING else RemoteTalkState.IDLE,
            lastSeenMs = now,
        )
        val entry = Entry(device, packet.talking, now, provisional)
        entries[packet.deviceId] = entry
        return entry
    }

    /** Periodic sweep: downgrades stale entries to OFFLINE and evicts long-dead ones. */
    fun sweep() {
        val now = nowMs()
        var changed = false
        for ((id, entry) in entries) {
            val age = now - entry.lastSeenMs
            if (age > evictAfterMs) {
                entries.remove(id)
                changed = true
            } else if (age > offlineAfterMs && entry.device.status == DeviceStatus.ONLINE) {
                changed = true
            }
        }
        if (changed) publish()
    }

    fun remove(deviceId: UUID) {
        entries.remove(deviceId)
        publish()
    }

    /** Drops everything (e.g. after a network switch to a different subnet). */
    fun clear() {
        entries.clear()
        publish()
    }

    fun snapshot(): List<Device> = _devices.value

    private fun publish() {
        val now = nowMs()
        val list = entries.values
            .map { entry ->
                val online = now - entry.lastSeenMs <= offlineAfterMs
                entry.device.copy(
                    status = if (online) DeviceStatus.ONLINE else DeviceStatus.OFFLINE,
                    talkState = if (online && entry.talking) RemoteTalkState.TALKING else RemoteTalkState.IDLE,
                    lastSeenMs = entry.lastSeenMs,
                )
            }
            .sortedWith(compareBy<Device> { it.name.lowercase() })
        _devices.value = list
    }
}
