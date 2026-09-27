package com.wavetalk.app.transport

import com.wavetalk.app.core.AppConstants
import com.wavetalk.app.core.WtLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Accepts encrypted audio/control connections from remote WaveTalk devices.
 *
 * Receiver-side half-duplex lock: only one talk session is active at a time;
 * a second talker receives a BUSY frame. Payloads arrive encrypted (client
 * stream) and are decrypted here in order, then dispatched.
 */
class TcpAudioServer(
    private val scope: CoroutineScope,
    private val selfId: UUID,
    private val selfName: () -> String,
    private val channelProvider: () -> String,
    private val listeners: Listeners,
) {

    interface Listeners {
        fun onSessionOpened(deviceId: UUID, name: String)
        fun onSessionClosed(deviceId: UUID)
        fun onTalkStarted(deviceId: UUID, name: String)
        fun onTalkStopped(deviceId: UUID)
        fun onAudioFrame(deviceId: UUID, payload: ByteArray)
        fun onServerFailed()
    }

    @Volatile var boundPort: Int = 0
        private set

    private var serverSocket: ServerSocket? = null

    @Volatile private var running = false
    @Volatile private var activeTalker: UUID? = null

    /** Diagnostics: last connection-level failure (used by tests and logs). */
    @Volatile var lastError: String? = null
        private set

    private val sessions = ConcurrentHashMap<UUID, Session>()

    class Session(
        val socket: Socket,
        val out: BufferedOutputStream,
        val encrypt: SessionCrypto.StreamCipher,
        val deviceId: UUID,
        val name: String,
    )

    fun start() {
        if (running) return
        running = true
        scope.launch(Dispatchers.IO) { acceptLoop() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        for (session in sessions.values) closeQuietly(session.socket)
        sessions.clear()
        activeTalker = null
    }

    fun isSessionActive(deviceId: UUID): Boolean = sessions.containsKey(deviceId)

    /** Sends a frame to a connected device. Fails (false) if the session is gone. */
    fun sendToDevice(deviceId: UUID, type: WireProtocol.FrameType, payload: ByteArray): Boolean {
        val session = sessions[deviceId] ?: return false
        synchronized(session) {
            return try {
                writeEncrypted(session, type, payload)
                true
            } catch (e: Exception) {
                WtLog.w(TAG, "Send to ${session.name} failed: ${e.message}")
                closeSession(session.deviceId)
                false
            }
        }
    }

    private fun writeEncrypted(session: Session, type: WireProtocol.FrameType, payload: ByteArray) {
        val encrypted = if (payload.isEmpty()) payload else session.encrypt.process(payload)
        WireProtocol.writeFrame(session.out, type, encrypted)
    }

    private suspend fun acceptLoop() {
        val server: ServerSocket? = try {
            var chosen: ServerSocket? = null
            for (offset in 0 until AppConstants.AUDIO_PORT_SPAN) {
                try {
                    val s = ServerSocket()
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(AppConstants.AUDIO_PORT_BASE + offset))
                    chosen = s
                    break
                } catch (_: Exception) {
                    // Port taken (e.g. second app instance) — try the next one.
                }
            }
            chosen
        } catch (e: Exception) {
            WtLog.e(TAG, "Server socket creation failed", e)
            null
        }

        if (server == null) {
            running = false
            listeners.onServerFailed()
            return
        }
        serverSocket = server
        boundPort = server.localPort
        WtLog.i(TAG, "Audio server listening on port $boundPort")

        while (running) {
            try {
                val client = server.accept()
                scope.launch(Dispatchers.IO) { handleConnection(client) }
            } catch (e: Exception) {
                if (running) {
                    WtLog.w(TAG, "Accept failed: ${e.message}")
                    delay(200)
                }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        var session: Session? = null
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = AppConstants.HANDSHAKE_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream().buffered())
            val out = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)

            // ---- Handshake ----
            val hello = WireProtocol.readHello(input)
            val myChannel = channelProvider()
            val channelOk = hello.talkScopeDirect || hello.channel.equals(myChannel, ignoreCase = true)

            val serverNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            if (!channelOk) {
                WireProtocol.writeWelcome(
                    out,
                    WireProtocol.Welcome(false, selfId, selfName(), serverNonce, WireProtocol.RejectReason.CHANNEL),
                )
                closeQuietly(socket)
                return
            }
            WireProtocol.writeWelcome(
                out,
                WireProtocol.Welcome(true, selfId, selfName(), serverNonce, WireProtocol.RejectReason.NONE),
            )

            val keys = SessionCrypto.deriveSessionKeys(
                hello.channel.ifBlank { myChannel }, hello.nonce, serverNonce,
            )
            val decrypt = SessionCrypto.StreamCipher(keys.aesKey, keys.ivUp)
            val encrypt = SessionCrypto.StreamCipher(keys.aesKey, keys.ivDown)

            socket.soTimeout = AppConstants.SOCKET_READ_TIMEOUT_MS
            val created = Session(socket, out, encrypt, hello.deviceId, hello.name.ifBlank { "Device" })
            session = created
            sessions.put(hello.deviceId, created)?.let { stale -> closeQuietly(stale.socket) }
            listeners.onSessionOpened(created.deviceId, created.name)
            WtLog.i(TAG, "Session opened from ${created.name}")

            // ---- Frame loop ----
            while (running) {
                val frame = WireProtocol.readFrame(input) ?: break
                val plain = if (frame.payload.isEmpty()) frame.payload else decrypt.process(frame.payload)
                when (frame.type) {
                    WireProtocol.FrameType.AUDIO -> {
                        if (activeTalker == null) {
                            activeTalker = created.deviceId
                            listeners.onTalkStarted(created.deviceId, created.name)
                        }
                        if (activeTalker == created.deviceId) {
                            listeners.onAudioFrame(created.deviceId, plain)
                        }
                        // Frames from a non-active talker are dropped (half-duplex).
                    }
                    WireProtocol.FrameType.TALK_START -> {
                        if (activeTalker == null || activeTalker == created.deviceId) {
                            activeTalker = created.deviceId
                            listeners.onTalkStarted(created.deviceId, created.name)
                        } else {
                            synchronized(created) { writeEncrypted(created, WireProtocol.FrameType.BUSY, ByteArray(0)) }
                        }
                    }
                    WireProtocol.FrameType.TALK_STOP -> {
                        if (activeTalker == created.deviceId) {
                            activeTalker = null
                            listeners.onTalkStopped(created.deviceId)
                        }
                    }
                    WireProtocol.FrameType.PING -> {
                        synchronized(created) { writeEncrypted(created, WireProtocol.FrameType.PONG, ByteArray(0)) }
                    }
                    WireProtocol.FrameType.BYE -> break
                    WireProtocol.FrameType.PONG, WireProtocol.FrameType.BUSY -> Unit
                }
            }
        } catch (e: Exception) {
            if (session != null) {
                lastError = "session ${session.name}: ${e::class.simpleName}: ${e.message}"
                WtLog.w(TAG, "Connection with ${session.name} ended: ${e.message}")
            } else {
                lastError = "handshake: ${e::class.simpleName}: ${e.message}"
                WtLog.w(TAG, "Handshake failed: ${e.message}")
            }
        } finally {
            session?.let { s ->
                sessions.remove(s.deviceId, s)
                if (activeTalker == s.deviceId) {
                    activeTalker = null
                    listeners.onTalkStopped(s.deviceId)
                }
                listeners.onSessionClosed(s.deviceId)
            }
            closeQuietly(socket)
        }
    }

    private fun closeSession(deviceId: UUID) {
        val session = sessions.remove(deviceId) ?: return
        closeQuietly(session.socket)
    }

    private fun closeQuietly(socket: Socket) {
        try { socket.close() } catch (_: Exception) {}
    }

    private companion object {
        const val TAG = "AudioServer"
    }
}
