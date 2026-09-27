package com.wavetalk.app.discovery

import com.wavetalk.app.core.AppConstants
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Binary discovery protocol over UDP.
 *
 * Layout (little-endian):
 *  [0..3]  magic "WTKD"
 *  [4]     protocol version
 *  [5]     packet type (see [DiscoveryType])
 *  [6..7]  capabilities bitmask
 *  [8..11] app protocol version (u32)
 *  [12..15] audio TCP port (u32)
 *  [16..31] device UUID (MSB first)
 *  [32]    name length (u8) + UTF-8 bytes
 *  [n]     channel length (u8) + UTF-8 bytes
 *  [m]     talk state (u8, 1 = talking) — present in every packet for simplicity
 */
enum class DiscoveryType(val code: Int) {
    ANNOUNCE(1), LEAVE(2), TALK_START(3), TALK_STOP(4);

    companion object {
        fun from(code: Int): DiscoveryType? = entries.firstOrNull { it.code == code }
    }
}

/** Bit flags advertised by this build. */
object DiscoveryCaps {
    const val ADPCM_AUDIO = 0x0001
    const val ENCRYPTED_TRANSPORT = 0x0002
    const val SUPPORTED = ADPCM_AUDIO or ENCRYPTED_TRANSPORT
}

data class DiscoveryPacket(
    val type: DiscoveryType,
    val deviceId: UUID,
    val audioPort: Int,
    val appVersion: Int,
    val capabilities: Int,
    val name: String,
    val channel: String,
    val talking: Boolean,
)

object DiscoveryCodec {
    private val MAGIC = byteArrayOf('W'.code.toByte(), 'T'.code.toByte(), 'K'.code.toByte(), 'D'.code.toByte())
    private const val HEADER_SIZE = 32

    /** Encodes [packet] into a freshly allocated datagram payload. */
    fun encode(packet: DiscoveryPacket): ByteArray {
        val nameBytes = packet.name.toByteArray(StandardCharsets.UTF_8).limitedTo(96)
        val channelBytes = packet.channel.toByteArray(StandardCharsets.UTF_8).limitedTo(64)
        val out = ByteArray(HEADER_SIZE + 2 + nameBytes.size + channelBytes.size + 1)

        MAGIC.copyInto(out, 0)
        out[4] = AppConstants.PROTOCOL_VERSION.toByte()
        out[5] = packet.type.code.toByte()
        out[6] = (packet.capabilities and 0xFF).toByte()
        out[7] = ((packet.capabilities shr 8) and 0xFF).toByte()
        writeU32(out, 8, packet.appVersion)
        writeU32(out, 12, packet.audioPort)
        val uuidBytes = longToBytes(packet.deviceId.mostSignificantBits, packet.deviceId.leastSignificantBits)
        uuidBytes.copyInto(out, 16)

        var off = HEADER_SIZE
        out[off++] = nameBytes.size.toByte()
        nameBytes.copyInto(out, off); off += nameBytes.size
        out[off++] = channelBytes.size.toByte()
        channelBytes.copyInto(out, off); off += channelBytes.size
        out[off] = if (packet.talking) 1 else 0
        return out
    }

    /**
     * Parses a datagram. Returns null for anything that is not a valid
     * WaveTalk discovery packet of a supported version — never throws.
     */
    fun decode(data: ByteArray, length: Int): DiscoveryPacket? {
        if (length < HEADER_SIZE + 3 || length > AppConstants.MAX_DISCOVERY_PACKET_BYTES) return null
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return null
        if (data[4].toInt() != AppConstants.PROTOCOL_VERSION) return null
        val type = DiscoveryType.from(data[5].toInt() and 0xFF) ?: return null

        val caps = (data[6].toInt() and 0xFF) or ((data[7].toInt() and 0xFF) shl 8)
        val appVersion = readU32(data, 8)
        val audioPort = readU32(data, 12)
        if (audioPort < 0 || audioPort > 65535) return null

        val msb = bytesToLong(data, 16)
        val lsb = bytesToLong(data, 24)
        val deviceId = UUID(msb, lsb)

        var off = HEADER_SIZE
        val nameLen = data[off].toInt() and 0xFF; off += 1
        if (off + nameLen + 1 > length) return null
        val name = String(data, off, nameLen, StandardCharsets.UTF_8); off += nameLen
        val channelLen = data[off].toInt() and 0xFF; off += 1
        if (off + channelLen + 1 > length) return null
        val channel = String(data, off, channelLen, StandardCharsets.UTF_8); off += channelLen
        val talking = (data[off].toInt() and 0xFF) == 1

        return DiscoveryPacket(type, deviceId, audioPort, appVersion, caps, name, channel, talking)
    }

    private fun writeU32(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        out[offset + 2] = ((value shr 16) and 0xFF).toByte()
        out[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun readU32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun longToBytes(msb: Long, lsb: Long): ByteArray {
        val out = ByteArray(16)
        for (i in 0 until 8) out[i] = (msb ushr ((7 - i) * 8)).toByte()
        for (i in 0 until 8) out[8 + i] = (lsb ushr ((7 - i) * 8)).toByte()
        return out
    }

    private fun bytesToLong(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value
    }

    private fun ByteArray.limitedTo(max: Int): ByteArray = if (size > max) copyOf(max) else this
}
