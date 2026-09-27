package com.wavetalk.app.discovery

import com.wavetalk.app.core.AppConstants
import com.wavetalk.app.core.WtLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID

/**
 * Sends and receives WaveTalk discovery datagrams on the local network.
 *
 * The socket and the send-target list are injected so the whole service runs
 * unchanged in JVM integration tests (unicast on loopback) and on Android
 * (broadcast + multicast on the Wi-Fi interface).
 */
class DiscoveryService(
    private val scope: CoroutineScope,
    private val presence: PresenceTracker,
    private val selfId: UUID,
    private val selfName: () -> String,
    private val selfChannel: () -> String,
    private val selfAudioPort: () -> Int,
    private val selfTalking: () -> Boolean,
    private val sendTargets: () -> List<SocketAddress>,
    private val socketFactory: () -> DatagramSocket,
    private val onFatal: (Throwable) -> Unit = {},
    private val announceIntervalMs: Long = AppConstants.ANNOUNCE_INTERVAL_MS,
) {

    @Volatile private var running = false
    private var receiveJob: Job? = null
    private var announceJob: Job? = null
    private var socket: DatagramSocket? = null

    fun start() {
        if (running) return
        running = true
        try {
            socket = socketFactory().apply { broadcast = true; reuseAddress = true }
        } catch (e: Exception) {
            running = false
            onFatal(e)
            return
        }
        receiveJob = scope.launch(Dispatchers.IO) { receiveLoop() }
        announceJob = scope.launch(Dispatchers.IO) { announceLoop() }
        WtLog.i(TAG, "Discovery started")
    }

    fun stop() {
        running = false
        announceJob?.cancel(); announceJob = null
        receiveJob?.cancel(); receiveJob = null
        socket?.close(); socket = null
        WtLog.i(TAG, "Discovery stopped")
    }

    /** Re-creates the socket and fires a fast announce burst (network changed). */
    fun restart() {
        stop()
        start()
        scope.launch(Dispatchers.IO) { burst() }
    }

    /** Immediate TALK_START / TALK_STOP notification to the whole network. */
    fun sendTalkEvent(started: Boolean) {
        val packet = selfPacket(if (started) DiscoveryType.TALK_START else DiscoveryType.TALK_STOP, talking = started)
        send(packet)
    }

    fun announceOnce() {
        send(selfPacket(DiscoveryType.ANNOUNCE, talking = selfTalking()))
    }

    private fun selfPacket(type: DiscoveryType, talking: Boolean): DiscoveryPacket = DiscoveryPacket(
        type = type,
        deviceId = selfId,
        audioPort = selfAudioPort(),
        appVersion = AppConstants.PROTOCOL_VERSION,
        capabilities = DiscoveryCaps.SUPPORTED,
        name = selfName(),
        channel = selfChannel(),
        talking = talking,
    )

    private suspend fun burst() {
        repeat(AppConstants.ANNOUNCE_BURST_COUNT) {
            if (!running) return
            announceOnce()
            delay(AppConstants.ANNOUNCE_BURST_INTERVAL_MS)
        }
    }

    private suspend fun announceLoop() {
        burst()
        while (running && scope.isActive) {
            delay(announceIntervalMs)
            announceOnce()
        }
    }

    private fun send(packet: DiscoveryPacket) {
        val sock = socket ?: return
        val payload = DiscoveryCodec.encode(packet)
        for (target in sendTargets()) {
            try {
                sock.send(DatagramPacket(payload, payload.size, target))
            } catch (e: Exception) {
                WtLog.w(TAG, "Send to $target failed: ${e.message}")
            }
        }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(AppConstants.MAX_DISCOVERY_PACKET_BYTES)
        while (running && scope.isActive) {
            val sock = socket ?: break
            try {
                val datagram = DatagramPacket(buffer, buffer.size)
                sock.receive(datagram)
                val packet = DiscoveryCodec.decode(datagram.data, datagram.length) ?: continue
                if (packet.deviceId == selfId) continue
                presence.onPacket(datagram.address ?: continue, packet)
            } catch (e: Exception) {
                if (!running) break
                WtLog.w(TAG, "Receive error: ${e.message}")
                delay(300)
            }
        }
    }

    private companion object {
        const val TAG = "Discovery"
    }
}
