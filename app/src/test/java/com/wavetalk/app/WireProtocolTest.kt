package com.wavetalk.app

import com.wavetalk.app.transport.SessionCrypto
import com.wavetalk.app.transport.WireProtocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireProtocolTest {

    @Test
    fun `frame write and read roundtrip`() {
        val out = ByteArrayOutputStream()
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        WireProtocol.writeFrame(out, WireProtocol.FrameType.AUDIO, payload)

        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        val frame = WireProtocol.readFrame(input)
        assertEquals(WireProtocol.FrameType.AUDIO, frame!!.type)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `empty payload frame roundtrip`() {
        val out = ByteArrayOutputStream()
        WireProtocol.writeFrame(out, WireProtocol.FrameType.PING, ByteArray(0))
        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        val frame = WireProtocol.readFrame(input)
        assertEquals(WireProtocol.FrameType.PING, frame!!.type)
        assertEquals(0, frame.payload.size)
    }

    @Test
    fun `clean EOF returns null`() {
        val input = DataInputStream(ByteArrayInputStream(ByteArray(0)))
        assertNull(WireProtocol.readFrame(input))
    }

    @Test
    fun `oversized frame length rejected`() {
        // type AUDIO, length 0xFFFF > MAX_FRAME_PAYLOAD
        val data = byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte())
        val input = DataInputStream(ByteArrayInputStream(data))
        try {
            WireProtocol.readFrame(input)
            assertFalse("Expected ProtocolException", true)
        } catch (e: WireProtocol.ProtocolException) {
            assertTrue(true)
        }
    }

    @Test
    fun `hello and welcome roundtrip`() {
        val nonce = ByteArray(16) { it.toByte() }
        val hello = WireProtocol.Hello(
            deviceId = UUID.nameUUIDFromBytes("client".toByteArray()),
            name = "هاتف سارة",
            channel = "Team",
            talkScopeDirect = false,
            nonce = nonce,
        )
        val out = ByteArrayOutputStream()
        WireProtocol.writeHello(out, hello)

        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        val parsed = WireProtocol.readHello(input)
        assertEquals(hello.deviceId, parsed.deviceId)
        assertEquals(hello.name, parsed.name)
        assertEquals(hello.channel, parsed.channel)
        assertFalse(parsed.talkScopeDirect)
        assertArrayEquals(nonce, parsed.nonce)

        val serverNonce = ByteArray(16) { (it * 3).toByte() }
        val welcome = WireProtocol.Welcome(true, UUID.randomUUID(), "Server", serverNonce, 0)
        val out2 = ByteArrayOutputStream()
        WireProtocol.writeWelcome(out2, welcome)
        val parsedWelcome = WireProtocol.readWelcome(DataInputStream(ByteArrayInputStream(out2.toByteArray())))
        assertTrue(parsedWelcome.ok)
        assertArrayEquals(serverNonce, parsedWelcome.nonce)
        assertEquals(welcome.deviceId, parsedWelcome.deviceId)
    }

    @Test
    fun `multiple frames keep stream alignment`() {
        val out = ByteArrayOutputStream()
        repeat(10) { i ->
            WireProtocol.writeFrame(out, WireProtocol.FrameType.AUDIO, ByteArray(166) { (it + i).toByte() })
        }
        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        repeat(10) { i ->
            val frame = WireProtocol.readFrame(input)
            assertEquals(166, frame!!.payload.size)
            assertEquals((0 + i).toByte(), frame.payload[0])
        }
        assertNull(WireProtocol.readFrame(input))
    }
}

class SessionCryptoTest {

    @Test
    fun `both sides derive identical keys from same material`() {
        val clientNonce = ByteArray(16) { (it * 5 + 1).toByte() }
        val serverNonce = ByteArray(16) { (it * 11 + 3).toByte() }
        val keys1 = SessionCrypto.deriveSessionKeys("Team", clientNonce, serverNonce)
        val keys2 = SessionCrypto.deriveSessionKeys("Team", clientNonce, serverNonce)
        assertArrayEquals(keys1.aesKey, keys2.aesKey)
        assertArrayEquals(keys1.ivUp, keys2.ivUp)
        assertArrayEquals(keys1.ivDown, keys2.ivDown)
    }

    @Test
    fun `different channel or nonces produce different keys`() {
        val c = ByteArray(16) { 1 }
        val s = ByteArray(16) { 2 }
        val a = SessionCrypto.deriveSessionKeys("Team", c, s)
        val b = SessionCrypto.deriveSessionKeys("Team2", c, s)
        assertFalse(a.aesKey.contentEquals(b.aesKey))
        val d = SessionCrypto.deriveSessionKeys("Team", c.copyOf().also { it[0] = 9 }, s)
        assertFalse(a.aesKey.contentEquals(d.aesKey))
    }

    @Test
    fun `stream cipher roundtrip preserves payload and advances state`() {
        val keys = SessionCrypto.deriveSessionKeys("Office", ByteArray(16) { 7 }, ByteArray(16) { 9 })
        val encrypt = SessionCrypto.StreamCipher(keys.aesKey, keys.ivUp)
        val decrypt = SessionCrypto.StreamCipher(keys.aesKey, keys.ivUp)

        val messages = listOf(
            ByteArray(166) { 1 },
            ByteArray(120) { 2 },
            ByteArray(166) { 3 },
        )
        messages.forEachIndexed { index, msg ->
            val ciphered = encrypt.process(msg)
            assertFalse("Message $index must not be plaintext", ciphered.contentEquals(msg))
            assertArrayEquals("Message $index roundtrip", msg, decrypt.process(ciphered))
        }
    }

    @Test
    fun `channel secret is case and whitespace insensitive`() {
        assertArrayEquals(
            SessionCrypto.channelSecret("  Team "),
            SessionCrypto.channelSecret("team"),
        )
    }
}
