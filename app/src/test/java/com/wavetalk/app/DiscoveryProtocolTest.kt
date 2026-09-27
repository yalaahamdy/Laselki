package com.wavetalk.app

import com.wavetalk.app.discovery.DiscoveryCaps
import com.wavetalk.app.discovery.DiscoveryCodec
import com.wavetalk.app.discovery.DiscoveryPacket
import com.wavetalk.app.discovery.DiscoveryType
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryProtocolTest {

    private val packet = DiscoveryPacket(
        type = DiscoveryType.ANNOUNCE,
        deviceId = UUID.nameUUIDFromBytes("device-a".toByteArray()),
        audioPort = 48896,
        appVersion = 1,
        capabilities = DiscoveryCaps.SUPPORTED,
        name = "Ahmed's Phone",
        channel = "Team",
        talking = false,
    )

    @Test
    fun `announce roundtrip preserves all fields`() {
        val bytes = DiscoveryCodec.encode(packet)
        val parsed = DiscoveryCodec.decode(bytes, bytes.size)
        assertTrue(parsed != null)
        assertEquals(packet.type, parsed!!.type)
        assertEquals(packet.deviceId, parsed.deviceId)
        assertEquals(packet.audioPort, parsed.audioPort)
        assertEquals(packet.name, parsed.name)
        assertEquals(packet.channel, parsed.channel)
        assertEquals(packet.talking, parsed.talking)
        assertEquals(packet.capabilities, parsed.capabilities)
    }

    @Test
    fun `talk start event roundtrip carries talking flag`() {
        val bytes = DiscoveryCodec.encode(packet.copy(type = DiscoveryType.TALK_START, talking = true))
        val parsed = DiscoveryCodec.decode(bytes, bytes.size)
        assertEquals(DiscoveryType.TALK_START, parsed!!.type)
        assertTrue(parsed.talking)
    }

    @Test
    fun `arabic and long names survive roundtrip`() {
        val bytes = DiscoveryCodec.encode(packet.copy(name = "هاتف أحمد الطويل جداً في الشبكة", channel = "قناة العائلة"))
        val parsed = DiscoveryCodec.decode(bytes, bytes.size)
        assertEquals("هاتف أحمد الطويل جداً في الشبكة", parsed!!.name)
        assertEquals("قناة العائلة", parsed.channel)
    }

    @Test
    fun `wrong magic is rejected`() {
        val bytes = DiscoveryCodec.encode(packet)
        bytes[0] = 'X'.code.toByte()
        assertNull(DiscoveryCodec.decode(bytes, bytes.size))
    }

    @Test
    fun `unsupported version is rejected`() {
        val bytes = DiscoveryCodec.encode(packet)
        bytes[4] = 99
        assertNull(DiscoveryCodec.decode(bytes, bytes.size))
    }

    @Test
    fun `truncated packet is rejected`() {
        val bytes = DiscoveryCodec.encode(packet)
        assertNull(DiscoveryCodec.decode(bytes, bytes.size / 2))
    }

    @Test
    fun `garbage is rejected`() {
        val bytes = ByteArray(200) { (it * 31 + 7).toByte() }
        assertNull(DiscoveryCodec.decode(bytes, bytes.size))
    }
}
