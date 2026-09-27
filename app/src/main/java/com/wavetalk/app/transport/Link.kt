package com.wavetalk.app.transport

import com.wavetalk.app.core.AppConstants
import com.wavetalk.app.core.WtLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Outbound encrypted audio/control connection to one remote device.
 *
 * Lifecycle: connect() performs the handshake; sendFrame() streams audio;
 * a keepalive ping keeps NAT tables and dead peers detectable. Any I/O
 * failure closes the link; the next talk press reconnects lazily.
 */
class Link(
    private val scope: CoroutineScope,
    val remoteDeviceId: UUID,
    private val remoteHost: String,
    private val remotePort: Int,
    private val selfId: UUID,
    private val selfName: () -> String,
    private val channelProvider: () -> String,
    private val directScope: Boolean,
    private val events: Events,
) {

    interface Events {
        fun onLinkReady(link: Link)
        fun onLinkClosed(link: Link)
        fun onBusyReceived(link: Link)
    }

    enum class State { CONNECTING, READY, CLOSED }

    private val state = AtomicReference(State.CONNECTING)
    val isReady: Boolean get() = state.get() == State.READY
    val remoteAddress: String = "$remoteHost:$remotePort"

    /** Diagnostics: last failure reason (also asserted in integration tests). */
    @Volatile var lastError: String? = null
        private set

    private var socket: Socket? = null
    private var out: BufferedOutputStream? = null
    private var encrypt: SessionCrypto.StreamCipher? = null
    private var readerJob: Job? = null
    private var pingJob: Job? = null
    private val sendLock = Any()

    /** Blocking connect + handshake. Returns true when READY. */
    fun connect(): Boolean {
        if (!state.compareAndSet(State.CONNECTING, State.CONNECTING)) {
            if (state.get() == State.READY) return true
            return false
        }
        val sock = Socket()
        try {
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(remoteHost, remotePort), AppConstants.CONNECT_TIMEOUT_MS)
            sock.soTimeout = AppConstants.HANDSHAKE_TIMEOUT_MS

            val input = DataInputStream(sock.getInputStream().buffered())
            val outStream = BufferedOutputStream(sock.getOutputStream(), 16 * 1024)

            val clientNonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            WireProtocol.writeHello(
                outStream,
                WireProtocol.Hello(selfId, selfName(), channelProvider(), directScope, clientNonce),
            )
            val welcome = WireProtocol.readWelcome(input)
            if (!welcome.ok) {
                lastError = "rejected by remote: reason=${welcome.reason}"
                WtLog.w(TAG, "Rejected by remote: reason=${welcome.reason}")
                sock.close()
                state.set(State.CLOSED)
                return false
            }
            if (welcome.deviceId != remoteDeviceId) {
                lastError = "remote identity mismatch"
                WtLog.w(TAG, "Remote identity mismatch")
                sock.close()
                state.set(State.CLOSED)
                return false
            }
            val keys = SessionCrypto.deriveSessionKeys(channelProvider(), clientNonce, welcome.nonce)
            encrypt = SessionCrypto.StreamCipher(keys.aesKey, keys.ivUp)   // client → server
            // Decrypt direction for server→client frames:
            val decrypt = SessionCrypto.StreamCipher(keys.aesKey, keys.ivDown)

            socket = sock
            out = outStream
            sock.soTimeout = AppConstants.SOCKET_READ_TIMEOUT_MS
            state.set(State.READY)

            readerJob = scope.launch(Dispatchers.IO) { readLoop(input, decrypt) }
            pingJob = scope.launch(Dispatchers.IO) { pingLoop() }
            events.onLinkReady(this)
            WtLog.i(TAG, "Link ready to $remoteHost:$remotePort")
            return true
        } catch (e: Exception) {
            lastError = "connect: ${e::class.simpleName}: ${e.message}"
            WtLog.w(TAG, "Connect to $remoteHost:$remotePort failed: ${e.message}")
            try { sock.close() } catch (_: Exception) {}
            state.set(State.CLOSED)
            events.onLinkClosed(this)
            return false
        }
    }

    fun sendFrame(type: WireProtocol.FrameType, payload: ByteArray): Boolean {
        if (state.get() != State.READY) return false
        val outStream = out ?: return false
        val enc = encrypt ?: return false
        return try {
            synchronized(sendLock) {
                val encrypted = if (payload.isEmpty()) payload else enc.process(payload)
                WireProtocol.writeFrame(outStream, type, encrypted)
            }
            true
        } catch (e: Exception) {
            WtLog.w(TAG, "Send failed, closing link: ${e.message}")
            close()
            false
        }
    }

    fun close() {
        if (!state.compareAndSet(State.READY, State.CLOSED) &&
            !state.compareAndSet(State.CONNECTING, State.CLOSED)
        ) return
        pingJob?.cancel()
        readerJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
        events.onLinkClosed(this)
    }

    private suspend fun readLoop(input: DataInputStream, decrypt: SessionCrypto.StreamCipher) {
        try {
            while (state.get() == State.READY) {
                val frame = WireProtocol.readFrame(input) ?: break
                when (frame.type) {
                    WireProtocol.FrameType.BUSY -> {
                        events.onBusyReceived(this)
                    }
                    WireProtocol.FrameType.BYE -> break
                    else -> Unit // PONG and anything else need no action here
                }
            }
        } catch (_: Exception) {
            // timeout / reset → fall through to close
        }
        if (state.get() == State.READY) close()
    }

    private suspend fun pingLoop() {
        while (state.get() == State.READY && scope.isActive) {
            delay(AppConstants.PING_INTERVAL_MS)
            if (!sendFrame(WireProtocol.FrameType.PING, ByteArray(0))) return
        }
    }

    private companion object {
        const val TAG = "Link"
    }
}
