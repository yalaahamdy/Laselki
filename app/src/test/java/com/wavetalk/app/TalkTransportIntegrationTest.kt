package com.wavetalk.app

import com.wavetalk.app.audio.AdpcmCodec
import com.wavetalk.app.transport.Link
import com.wavetalk.app.transport.TcpAudioServer
import com.wavetalk.app.transport.WireProtocol
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real end-to-end integration test over the loopback network:
 *
 *   Link (client)  ──TCP──▶  TcpAudioServer
 *     handshake → encrypted ADPCM frames → receiver events
 *
 * This exercises the full production transport path: WireProtocol framing,
 * SessionCrypto AES-CTR key derivation and streaming, talk-state frames and
 * the receiver-side half-duplex lock — everything except the Android audio
 * hardware itself.
 */
class TalkTransportIntegrationTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = CopyOnWriteArrayList<kotlinx.coroutines.Job>()

    private class ServerEvents : TcpAudioServer.Listeners {
        val sessionsOpened = CopyOnWriteArrayList<UUID>()
        val sessionsClosed = CopyOnWriteArrayList<UUID>()
        val talkStarts = CopyOnWriteArrayList<UUID>()
        val talkStops = CopyOnWriteArrayList<UUID>()
        val audioFrames = CopyOnWriteArrayList<Pair<UUID, ByteArray>>()
        val failed = AtomicBoolean(false)

        override fun onSessionOpened(deviceId: UUID, name: String) { sessionsOpened.add(deviceId) }
        override fun onSessionClosed(deviceId: UUID) { sessionsClosed.add(deviceId) }
        override fun onTalkStarted(deviceId: UUID, name: String) { talkStarts.add(deviceId) }
        override fun onTalkStopped(deviceId: UUID) { talkStops.add(deviceId) }
        override fun onAudioFrame(deviceId: UUID, payload: ByteArray) { audioFrames.add(deviceId to payload) }
        override fun onServerFailed() { failed.set(true) }
    }

    private lateinit var server: TcpAudioServer
    private lateinit var events: ServerEvents

    private val serverId = UUID.nameUUIDFromBytes("server-device".toByteArray())
    private val clientAId = UUID.nameUUIDFromBytes("client-a".toByteArray())
    private val clientBId = UUID.nameUUIDFromBytes("client-b".toByteArray())

    @Before
    fun setUp() {
        events = ServerEvents()
        server = TcpAudioServer(scope, serverId, { "TestServer" }, { "Team" }, events)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        jobs.forEach { it.cancel() }
    }

    private suspend fun awaitPort(): Int {
        withTimeout(5_000) {
            while (server.boundPort == 0) delay(20)
        }
        return server.boundPort
    }

    private fun connectClient(
        selfId: UUID,
        port: Int,
        channel: String = "Team",
        direct: Boolean = false,
        onBusy: () -> Unit = {},
        remoteId: UUID = serverId,
    ): Link {
        return Link(
            scope = scope,
            remoteDeviceId = remoteId,
            remoteHost = "127.0.0.1",
            remotePort = port,
            selfId = selfId,
            selfName = { "Client-$selfId" },
            channelProvider = { channel },
            directScope = direct,
            events = object : Link.Events {
                override fun onLinkReady(link: Link) {}
                override fun onLinkClosed(link: Link) {}
                override fun onBusyReceived(link: Link) {
                    onBusy()
                }
            },
        )
    }

    @Test
    fun `audio streams end to end over real tcp with encryption`() = runBlocking {
        val port = awaitPort()
        assertTrue("Server failed to bind", !events.failed.get())

        val link = connectClient(clientAId, port)
        val connected = link.connect()
        assertTrue(
            "Handshake failed: link=${link.lastError}, server=${server.lastError}",
            connected,
        )
        assertTrue(link.isReady)

        // Talk start
        assertTrue(link.sendFrame(WireProtocol.FrameType.TALK_START, ByteArray(0)))

        // Stream 100 continuous sine frames (2 seconds of audio), as fast as TCP allows
        var phase = 0.0
        val originals = ArrayList<ShortArray>(100)
        repeat(100) { i ->
            val pcm = ShortArray(AdpcmCodec.FRAME_SAMPLES) { j ->
                (9000.0 * sin(phase + j * 2 * PI * 440.0 / 16000.0)).toInt().toShort()
            }
            phase += AdpcmCodec.FRAME_SAMPLES * 2 * PI * 440.0 / 16000.0
            originals.add(pcm)

            val encoded = AdpcmCodec.encode(pcm)
            val payload = ByteArray(2 + encoded.size)
            payload[0] = (i and 0xFF).toByte()
            payload[1] = ((i shr 8) and 0xFF).toByte()
            encoded.copyInto(payload, 2)
            assertTrue("Frame $i send failed", link.sendFrame(WireProtocol.FrameType.AUDIO, payload))
        }

        assertTrue(link.sendFrame(WireProtocol.FrameType.TALK_STOP, ByteArray(0)))

        withTimeout(5_000) {
            while (events.audioFrames.size < 100) delay(20)
        }
        withTimeout(3_000) {
            while (events.talkStops.isEmpty()) delay(20)
        }

        // All frames arrived, in order, decryptable, from the right talker
        assertEquals(clientAId, events.talkStarts.first())
        assertEquals(clientAId, events.audioFrames[0].first)
        assertEquals(100, events.audioFrames.size)

        // Sequence numbers must be intact (TCP ordering + no corruption)
        assertEquals(0, events.audioFrames[0].second[0].toInt())
        assertEquals(99, events.audioFrames[99].second[0].toInt())

        // Decrypted audio must still be high quality
        for (idx in intArrayOf(0, 50, 99)) {
            val received = events.audioFrames[idx].second
            val decoded = AdpcmCodec.decode(received, 2, received.size - 2)
            val ref = originals[idx]
            var signal = 0.0
            var noise = 0.0
            for (s in ref.indices) {
                signal += ref[s].toDouble() * ref[s]
                noise += (ref[s] - decoded[s]).toDouble() * (ref[s] - decoded[s])
            }
            val snr = 10.0 * log10(signal / noise)
            assertTrue("SNR frame $idx was $snr dB", snr > 15.0)
        }

        link.close()
    }

    @Test
    fun `second simultaneous talker receives busy`() = runBlocking {
        val port = awaitPort()
        val busyFlag = AtomicBoolean(false)
        val linkA = connectClient(clientAId, port)
        val linkB = connectClient(clientBId, port, onBusy = { busyFlag.set(true) })

        assertTrue(linkA.connect())
        assertTrue(linkB.connect())
        assertTrue(linkA.sendFrame(WireProtocol.FrameType.TALK_START, ByteArray(0)))
        withTimeout(3_000) {
            while (events.talkStarts.isEmpty()) delay(20)
        }

        // A streams one frame to hold the floor
        val encoded = AdpcmCodec.encode(ShortArray(AdpcmCodec.FRAME_SAMPLES) { 1000 })
        val payload = ByteArray(2 + encoded.size)
        encoded.copyInto(payload, 2)
        assertTrue(linkA.sendFrame(WireProtocol.FrameType.AUDIO, payload))
        withTimeout(3_000) {
            while (events.audioFrames.isEmpty()) delay(20)
        }

        // B tries to talk while A holds the floor → BUSY
        assertTrue(linkB.sendFrame(WireProtocol.FrameType.TALK_START, ByteArray(0)))
        withTimeout(3_000) {
            while (!busyFlag.get()) delay(20)
        }

        // A's stream must be untouched by B's attempt
        assertTrue(events.audioFrames.all { it.first == clientAId })

        linkA.close()
        linkB.close()
    }

    @Test
    fun `wrong channel is rejected on channel scope`() = runBlocking {
        val port = awaitPort()
        val link = connectClient(clientAId, port, channel = "OtherChannel", direct = false)
        val connected = link.connect()
        assertTrue(
            "Expected channel rejection (link=${link.lastError}, server=${server.lastError})",
            !connected,
        )
        assertTrue(!link.isReady)
    }

    @Test
    fun `direct scope connects across channels`() = runBlocking {
        val port = awaitPort()
        val link = connectClient(clientAId, port, channel = "Private", direct = true)
        val connected = link.connect()
        assertTrue(
            "Direct-target talk should be accepted (link=${link.lastError}, server=${server.lastError})",
            connected,
        )
        link.close()
    }

    @Test
    fun `server survives abrupt client disconnect`() = runBlocking {
        val port = awaitPort()
        val link = connectClient(clientAId, port)
        val connected = link.connect()
        assertTrue("First connect failed: ${link.lastError}", connected)
        link.close() // abrupt teardown from the client side

        withTimeout(3_000) {
            while (events.sessionsClosed.isEmpty()) delay(20)
        }
        // Server must still accept a new session
        val link2 = connectClient(clientAId, port)
        val connected2 = link2.connect()
        assertTrue("Second connect failed: ${link2.lastError}", connected2)
        link2.close()
    }
}
