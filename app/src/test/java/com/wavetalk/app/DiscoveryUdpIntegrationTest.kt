package com.wavetalk.app

import com.wavetalk.app.discovery.DiscoveryCodec
import com.wavetalk.app.discovery.DiscoveryPacket
import com.wavetalk.app.discovery.DiscoveryService
import com.wavetalk.app.discovery.DiscoveryType
import com.wavetalk.app.discovery.PresenceTracker
import com.wavetalk.app.domain.DeviceStatus
import com.wavetalk.app.domain.RemoteTalkState
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real UDP integration test on the loopback interface: two DiscoveryService
 * instances announce themselves, hear each other, and propagate talk events —
 * the exact datagram path used on a Wi-Fi network.
 */
class DiscoveryUdpIntegrationTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val cleanup = ArrayList<DiscoveryService>()
    private val sockets = ArrayList<DatagramSocket>()

    private class Node(
        val id: UUID,
        val name: String,
        val presence: PresenceTracker,
        val socket: DatagramSocket,
        val service: DiscoveryService,
    )

    private fun makeNode(
        name: String,
        channel: String,
        peerPort: Int,
    ): Node {
        val id = UUID.nameUUIDFromBytes(name.toByteArray())
        val presence = PresenceTracker(offlineAfterMs = 60_000, evictAfterMs = 120_000)
        val socket = DatagramSocket(InetSocketAddress(loopback, 0)).also { sockets.add(it) }
        val service = DiscoveryService(
            scope = scope,
            presence = presence,
            selfId = id,
            selfName = { name },
            selfChannel = { channel },
            selfAudioPort = { 48896 },
            selfTalking = { false },
            sendTargets = { listOf(InetSocketAddress(loopback, peerPort)) },
            socketFactory = { socket },
            announceIntervalMs = 150,
        ).also { cleanup.add(it) }
        return Node(id, name, presence, socket, service)
    }

    private fun pairedNodes(nameA: String, nameB: String, channel: String): Pair<Node, Node> {
        // Ports must exist before services are built, so bind throwaway sockets
        // to reserve nothing — instead build A first with a placeholder target,
        // but targets are lambdas, so we can reference a late-set holder.
        val portHolderB = intArrayOf(0)
        val portHolderA = intArrayOf(0)

        val socketA = DatagramSocket(InetSocketAddress(loopback, 0)).also { sockets.add(it) }
        portHolderA[0] = socketA.localPort
        val socketB = DatagramSocket(InetSocketAddress(loopback, 0)).also { sockets.add(it) }
        portHolderB[0] = socketB.localPort

        val presenceA = PresenceTracker(offlineAfterMs = 60_000, evictAfterMs = 120_000)
        val presenceB = PresenceTracker(offlineAfterMs = 60_000, evictAfterMs = 120_000)
        val idA = UUID.nameUUIDFromBytes(nameA.toByteArray())
        val idB = UUID.nameUUIDFromBytes(nameB.toByteArray())

        val serviceA = DiscoveryService(
            scope, presenceA, idA, { nameA }, { channel }, { 48896 }, { false },
            sendTargets = { listOf(InetSocketAddress(loopback, portHolderB[0])) },
            socketFactory = { socketA },
            announceIntervalMs = 150,
        ).also { cleanup.add(it) }

        val serviceB = DiscoveryService(
            scope, presenceB, idB, { nameB }, { channel }, { 48896 }, { false },
            sendTargets = { listOf(InetSocketAddress(loopback, portHolderA[0])) },
            socketFactory = { socketB },
            announceIntervalMs = 150,
        ).also { cleanup.add(it) }

        return Pair(
            Node(idA, nameA, presenceA, socketA, serviceA),
            Node(idB, nameB, presenceB, socketB, serviceB),
        )
    }

    @Test
    fun `two nodes discover each other over real udp`() = runBlocking {
        val (a, b) = pairedNodes("Ahmed", "Sara", "Team")
        a.service.start()
        b.service.start()

        withTimeout(5_000) {
            while (b.presence.snapshot().none { it.id == a.id }) delay(25)
        }
        withTimeout(5_000) {
            while (a.presence.snapshot().none { it.id == b.id }) delay(25)
        }

        val seenByB = b.presence.snapshot().first { it.id == a.id }
        assertEquals("Ahmed", seenByB.name)
        assertEquals("Team", seenByB.channel)
        assertEquals(DeviceStatus.ONLINE, seenByB.status)

        val seenByA = a.presence.snapshot().first { it.id == b.id }
        assertEquals("Sara", seenByA.name)
    }

    @Test
    fun `talk event propagates instantly to the peer`() = runBlocking {
        val (a, b) = pairedNodes("Ahmed", "Sara", "Team")
        a.service.start()
        b.service.start()

        withTimeout(5_000) {
            while (b.presence.snapshot().isEmpty()) delay(25)
        }

        a.service.sendTalkEvent(started = true)
        withTimeout(3_000) {
            while (b.presence.snapshot().firstOrNull()?.talkState != RemoteTalkState.TALKING) delay(25)
        }

        a.service.sendTalkEvent(started = false)
        withTimeout(3_000) {
            while (b.presence.snapshot().firstOrNull()?.talkState != RemoteTalkState.IDLE) delay(25)
        }
    }

    @Test
    fun `leave event removes the peer`() = runBlocking {
        val (a, b) = pairedNodes("Ahmed", "Sara", "Team")
        a.service.start()
        b.service.start()

        withTimeout(5_000) {
            while (b.presence.snapshot().isEmpty()) delay(25)
        }

        // Construct a LEAVE datagram exactly like the wire format and inject
        // it through the real codec path at B's presence tracker.
        val leave = DiscoveryPacket(
            type = DiscoveryType.LEAVE,
            deviceId = a.id,
            audioPort = 48896,
            appVersion = 1,
            capabilities = 3,
            name = "Ahmed",
            channel = "Team",
            talking = false,
        )
        val bytes = DiscoveryCodec.encode(leave)
        val parsed = DiscoveryCodec.decode(bytes, bytes.size)
        assertTrue(parsed != null)
        b.presence.onPacket(loopback, parsed!!)

        withTimeout(2_000) {
            while (b.presence.snapshot().isNotEmpty()) delay(25)
        }
        assertTrue(b.presence.snapshot().isEmpty())
    }
}
