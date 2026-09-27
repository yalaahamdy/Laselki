package com.wavetalk.app

import com.wavetalk.app.discovery.DiscoveryPacket
import com.wavetalk.app.discovery.DiscoveryType
import com.wavetalk.app.discovery.PresenceTracker
import com.wavetalk.app.domain.DeviceStatus
import com.wavetalk.app.domain.RemoteTalkState
import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceTrackerTest {

    private val host: InetAddress = InetAddress.getByName("192.168.1.50")

    private fun packet(
        type: DiscoveryType,
        id: UUID = UUID.nameUUIDFromBytes("a".toByteArray()),
        name: String = "Ahmed",
        channel: String = "Team",
        talking: Boolean = false,
    ) = DiscoveryPacket(
        type = type,
        deviceId = id,
        audioPort = 48896,
        appVersion = 1,
        capabilities = 3,
        name = name,
        channel = channel,
        talking = talking,
    )

    @Test
    fun `announcement makes device online`() = runTest {
        var now = 0L
        val tracker = PresenceTracker(nowMs = { now })
        tracker.onPacket(host, packet(DiscoveryType.ANNOUNCE))

        val devices = tracker.devices.first { it.isNotEmpty() }
        assertEquals(1, devices.size)
        assertEquals(DeviceStatus.ONLINE, devices[0].status)
        assertEquals(RemoteTalkState.IDLE, devices[0].talkState)
        assertEquals("Ahmed", devices[0].name)
    }

    @Test
    fun `stale device goes offline then is evicted`() = runTest {
        var now = 0L
        val tracker = PresenceTracker(nowMs = { now }, offlineAfterMs = 4_000, evictAfterMs = 10_000)
        tracker.onPacket(host, packet(DiscoveryType.ANNOUNCE))

        now = 4_500
        tracker.sweep()
        assertEquals(DeviceStatus.OFFLINE, tracker.snapshot()[0].status)

        now = 10_500
        tracker.sweep()
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun `refresh brings device back online`() = runTest {
        var now = 0L
        val tracker = PresenceTracker(nowMs = { now }, offlineAfterMs = 4_000, evictAfterMs = 10_000)
        tracker.onPacket(host, packet(DiscoveryType.ANNOUNCE))

        now = 5_000
        tracker.sweep()
        assertEquals(DeviceStatus.OFFLINE, tracker.snapshot()[0].status)

        now = 5_100
        tracker.onPacket(host, packet(DiscoveryType.ANNOUNCE))
        assertEquals(DeviceStatus.ONLINE, tracker.snapshot()[0].status)
    }

    @Test
    fun `talk start and stop update speaking state instantly`() = runTest {
        var now = 0L
        val tracker = PresenceTracker(nowMs = { now })
        val id = UUID.nameUUIDFromBytes("a".toByteArray())
        tracker.onPacket(host, packet(DiscoveryType.ANNOUNCE, id = id))

        tracker.onPacket(host, packet(DiscoveryType.TALK_START, id = id, talking = true))
        assertEquals(RemoteTalkState.TALKING, tracker.snapshot()[0].talkState)

        // Subsequent announces keep the talking flag while it lasts.
        now = 1_200
        tracker.onPacket(host, packet(DiscoveryType.ANNOUNCE, id = id, talking = true))
        assertEquals(RemoteTalkState.TALKING, tracker.snapshot()[0].talkState)

        tracker.onPacket(host, packet(DiscoveryType.TALK_STOP, id = id))
        assertEquals(RemoteTalkState.IDLE, tracker.snapshot()[0].talkState)
    }

    @Test
    fun `leave removes the device immediately`() = runTest {
        var now = 0L
        val tracker = PresenceTracker(nowMs = { now })
        val id = UUID.nameUUIDFromBytes("a".toByteArray())
        tracker.onPacket(host, packet(DiscoveryType.ANNOUNCE, id = id))
        tracker.onPacket(host, packet(DiscoveryType.LEAVE, id = id))
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun `devices are sorted by name and refreshed announcements update fields`() = runTest {
        var now = 0L
        val tracker = PresenceTracker(nowMs = { now })
        tracker.onPacket(host, packet(DiscoveryType.ANNOUNCE, name = "Ziad"))
        tracker.onPacket(host, packet(DiscoveryType.ANNOUNCE, id = UUID.nameUUIDFromBytes("b".toByteArray()), name = "Ahmed"))

        val names = tracker.snapshot().map { it.name }
        assertEquals(listOf("Ahmed", "Ziad"), names)

        tracker.onPacket(
            host,
            packet(DiscoveryType.ANNOUNCE, id = UUID.nameUUIDFromBytes("b".toByteArray()), name = "Ahmed", channel = "Office"),
        )
        assertEquals("Office", tracker.snapshot().first { it.name == "Ahmed" }.channel)
    }

    @Test
    fun `clear drops everything`() = runTest {
        var now = 0L
        val tracker = PresenceTracker(nowMs = { now })
        tracker.onPacket(host, packet(DiscoveryType.ANNOUNCE))
        tracker.clear()
        assertTrue(tracker.snapshot().isEmpty())
    }
}
