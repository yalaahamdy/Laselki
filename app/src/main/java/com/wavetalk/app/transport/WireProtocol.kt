package com.wavetalk.app.transport

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Wire framing for the audio/control TCP channel.
 *
 * Post-handshake frames:   [type u8][payloadLen u16 LE][payload]   (payload encrypted)
 * Handshake messages:      [magic "WTKH"][ver u8][type u8][len u16 LE][body]  (plaintext)
 */
object WireProtocol {

    const val HANDSHAKE_MAGIC_0 = 'W'.code.toByte()
    const val HANDSHAKE_MAGIC_1 = 'T'.code.toByte()
    const val HANDSHAKE_MAGIC_2 = 'K'.code.toByte()
    const val HANDSHAKE_MAGIC_3 = 'H'.code.toByte()

    enum class HandshakeType(val code: Int) {
        HELLO(1), WELCOME(2), REJECT(3);

        companion object {
            fun from(code: Int): HandshakeType? = entries.firstOrNull { it.code == code }
        }
    }

    enum class FrameType(val code: Int) {
        AUDIO(1), TALK_START(2), TALK_STOP(3), PING(4), PONG(5), BYE(6), BUSY(7);

        companion object {
            fun from(code: Int): FrameType? = entries.firstOrNull { it.code == code }
        }
    }

    /** Reason codes carried by REJECT / WELCOME(ok=false). */
    object RejectReason {
        const val NONE = 0
        const val VERSION = 1
        const val CHANNEL = 2
        const val PROTOCOL = 3
    }

    /** Client → server opening message. */
    data class Hello(
        val deviceId: UUID,
        val name: String,
        val channel: String,
        val talkScopeDirect: Boolean,
        val nonce: ByteArray,
    )

    /** Server → client response. */
    data class Welcome(
        val ok: Boolean,
        val deviceId: UUID,
        val name: String,
        val nonce: ByteArray,
        val reason: Int,
    )

    class ProtocolException(message: String) : IOException(message)

    // ---------------- frames ----------------

    fun writeFrame(out: OutputStream, type: FrameType, payload: ByteArray) {
        if (payload.size > MAX_FRAME_PAYLOAD) throw ProtocolException("Frame too large: ${payload.size}")
        val header = ByteArray(3)
        header[0] = type.code.toByte()
        header[1] = (payload.size and 0xFF).toByte()
        header[2] = ((payload.size shr 8) and 0xFF).toByte()
        synchronized(out) {
            out.write(header)
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
        }
    }

    /**
     * Blocking frame read. Returns null on clean stream end.
     * @throws ProtocolException on malformed/oversized frames
     */
    fun readFrame(input: DataInputStream): Frame? {
        val typeByte: Int = try {
            input.readUnsignedByte()
        } catch (_: EOFException) {
            return null
        } catch (_: IOException) {
            return null
        }
        val type = FrameType.from(typeByte) ?: throw ProtocolException("Unknown frame type $typeByte")
        val lo = input.readUnsignedByte()
        val hi = input.readUnsignedByte()
        val len = lo or (hi shl 8)
        if (len > MAX_FRAME_PAYLOAD) throw ProtocolException("Frame length $len exceeds limit")
        val payload = ByteArray(len)
        input.readFully(payload)
        return Frame(type, payload)
    }

    data class Frame(val type: FrameType, val payload: ByteArray)

    const val MAX_FRAME_PAYLOAD = 4_096

    // ---------------- handshake ----------------

    fun writeHello(out: OutputStream, hello: Hello) {
        val name = hello.name.toByteArray(StandardCharsets.UTF_8).limitedTo(96)
        val channel = hello.channel.toByteArray(StandardCharsets.UTF_8).limitedTo(64)
        val body = ByteArray(16 + 16 + 1 + 1 + name.size + 1 + channel.size)
        uuidToBytes(hello.deviceId).copyInto(body, 0)
        hello.nonce.copyInto(body, 16)
        body[32] = if (hello.talkScopeDirect) 1 else 0
        body[33] = name.size.toByte()
        name.copyInto(body, 34)
        body[34 + name.size] = channel.size.toByte()
        channel.copyInto(body, 35 + name.size)
        writeHandshake(out, HandshakeType.HELLO, body)
    }

    fun readHello(input: DataInputStream): Hello {
        val body = readHandshakeBody(input, HandshakeType.HELLO)
        if (body.size < 35) throw ProtocolException("HELLO body too short")
        val deviceId = bytesToUuid(body, 0)
        val nonce = body.copyOfRange(16, 32)
        val direct = body[32].toInt() == 1
        val nameLen = body[33].toInt() and 0xFF
        if (34 + nameLen + 1 > body.size) throw ProtocolException("HELLO name overflow")
        val name = String(body, 34, nameLen, StandardCharsets.UTF_8)
        val channelLen = body[34 + nameLen].toInt() and 0xFF
        if (35 + nameLen + channelLen > body.size) throw ProtocolException("HELLO channel overflow")
        val channel = String(body, 35 + nameLen, channelLen, StandardCharsets.UTF_8)
        return Hello(deviceId, name, channel, direct, nonce)
    }

    fun writeWelcome(out: OutputStream, welcome: Welcome) {
        val name = welcome.name.toByteArray(StandardCharsets.UTF_8).limitedTo(96)
        val body = ByteArray(1 + 16 + 16 + 1 + 1 + name.size)
        body[0] = if (welcome.ok) 1 else 0
        uuidToBytes(welcome.deviceId).copyInto(body, 1)
        welcome.nonce.copyInto(body, 17)
        body[33] = welcome.reason.toByte()
        body[34] = name.size.toByte()
        name.copyInto(body, 35)
        writeHandshake(out, if (welcome.ok) HandshakeType.WELCOME else HandshakeType.REJECT, body)
    }

    fun readWelcome(input: DataInputStream): Welcome {
        val body = readHandshakeBody(input, null)
        if (body.size < 35) throw ProtocolException("WELCOME body too short")
        val ok = body[0].toInt() == 1
        val deviceId = bytesToUuid(body, 1)
        val nonce = body.copyOfRange(17, 33)
        val reason = body[33].toInt() and 0xFF
        val nameLen = body[34].toInt() and 0xFF
        if (35 + nameLen > body.size) throw ProtocolException("WELCOME name overflow")
        val name = String(body, 35, nameLen, StandardCharsets.UTF_8)
        return Welcome(ok, deviceId, name, nonce, reason)
    }

    private fun writeHandshake(out: OutputStream, type: HandshakeType, body: ByteArray) {
        if (body.size > MAX_FRAME_PAYLOAD) throw ProtocolException("Handshake body too large")
        val full = ByteArray(8)
        full[0] = HANDSHAKE_MAGIC_0
        full[1] = HANDSHAKE_MAGIC_1
        full[2] = HANDSHAKE_MAGIC_2
        full[3] = HANDSHAKE_MAGIC_3
        full[4] = PROTOCOL_VERSION_BYTE
        full[5] = type.code.toByte()
        full[6] = (body.size and 0xFF).toByte()
        full[7] = ((body.size shr 8) and 0xFF).toByte()
        synchronized(out) {
            out.write(full)
            out.write(body)
            out.flush()
        }
    }

    private fun readHandshakeBody(input: DataInputStream, expect: HandshakeType?): ByteArray {
        val magic = ByteArray(4)
        input.readFully(magic)
        if (magic[0] != HANDSHAKE_MAGIC_0 || magic[1] != HANDSHAKE_MAGIC_1 ||
            magic[2] != HANDSHAKE_MAGIC_2 || magic[3] != HANDSHAKE_MAGIC_3
        ) throw ProtocolException("Bad handshake magic")
        val ver = input.readUnsignedByte()
        if (ver != PROTOCOL_VERSION_BYTE.toInt()) throw ProtocolException("Unsupported handshake version $ver")
        val typeCode = input.readUnsignedByte()
        val type = HandshakeType.from(typeCode) ?: throw ProtocolException("Unknown handshake type $typeCode")
        if (expect != null && type != expect) throw ProtocolException("Expected $expect got $type")
        val lo = input.readUnsignedByte()
        val hi = input.readUnsignedByte()
        val len = lo or (hi shl 8)
        if (len > MAX_FRAME_PAYLOAD) throw ProtocolException("Handshake body too large")
        val body = ByteArray(len)
        input.readFully(body)
        return body
    }

    const val PROTOCOL_VERSION_BYTE: Byte = 1

    // ---------------- helpers ----------------

    fun uuidToBytes(uuid: UUID): ByteArray {
        val out = ByteArray(16)
        var v = uuid.mostSignificantBits
        for (i in 0 until 8) out[i] = (v ushr ((7 - i) * 8)).toByte()
        v = uuid.leastSignificantBits
        for (i in 0 until 8) out[8 + i] = (v ushr ((7 - i) * 8)).toByte()
        return out
    }

    fun bytesToUuid(data: ByteArray, offset: Int): UUID {
        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (data[offset + i].toLong() and 0xFF)
        for (i in 0 until 8) lsb = (lsb shl 8) or (data[offset + 8 + i].toLong() and 0xFF)
        return UUID(msb, lsb)
    }

    private fun ByteArray.limitedTo(max: Int): ByteArray = if (size > max) copyOf(max) else this
}
