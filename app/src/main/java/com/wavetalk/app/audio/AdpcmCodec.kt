package com.wavetalk.app.audio

import com.wavetalk.app.core.AppConstants

/**
 * IMA ADPCM codec (4-bit), pure Kotlin.
 *
 * Chosen over Opus/AAC because it is deterministic across all devices, has
 * negligible CPU cost and latency, and cuts raw PCM bandwidth 4x — an excellent
 * reliability/latency trade-off for a LAN walkie-talkie. Each 20ms frame carries
 * its own predictor state so frames are independently decodable (a listener can
 * join mid-stream and stays robust to stream restarts).
 *
 * Frame layout: [predictor s16-LE][index u8][reserved u8] + ceil(samples/2) nibble bytes
 * (low nibble first).
 */
object AdpcmCodec {

    private val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
        253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
        1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
        3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442,
        11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
        32767
    )

    private val INDEX_TABLE = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8
    )

    const val FRAME_SAMPLES: Int = AppConstants.FRAME_SAMPLES

    /** Bytes of one encoded frame (header + nibbles). */
    const val FRAME_BYTES: Int = 4 + FRAME_SAMPLES / 2

    fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size % 2 == 0) { "ADPCM encodes sample pairs; odd size ${pcm.size}" }
        val out = ByteArray(4 + pcm.size / 2)

        var predictor = pcm[0].toInt()
        var index = 6
        out[0] = (predictor and 0xFF).toByte()
        out[1] = ((predictor shr 8) and 0xFF).toByte()
        out[2] = index.toByte()
        out[3] = 0

        var nibblePos = 4
        var low = true
        for (i in pcm.indices) {
            val sample = pcm[i].toInt()

            val diff0 = sample - predictor
            val sign = diff0 < 0
            var diff = if (sign) -diff0 else diff0
            val step = STEP_TABLE[index]

            var delta = 0
            if (diff >= step) { delta = 4; diff -= step }
            if (diff >= (step shr 1)) { delta = delta or 2; diff -= step shr 1 }
            if (diff >= (step shr 2)) delta = delta or 1

            var diffq = step shr 3
            if (delta and 4 != 0) diffq += step
            if (delta and 2 != 0) diffq += step shr 1
            if (delta and 1 != 0) diffq += step shr 2
            predictor = clampShort(if (sign) predictor - diffq else predictor + diffq)
            index = (index + INDEX_TABLE[delta]).coerceIn(0, 88)

            val nibble = delta or (if (sign) 8 else 0)
            if (low) {
                out[nibblePos] = nibble.toByte()
            } else {
                out[nibblePos] = (out[nibblePos].toInt() or (nibble shl 4)).toByte()
                nibblePos++
            }
            low = !low
        }
        return out
    }

    fun decode(encoded: ByteArray): ShortArray = decode(encoded, 0, encoded.size)

    fun decode(encoded: ByteArray, offset: Int, length: Int): ShortArray {
        require(length >= 5) { "Encoded frame too short: $length" }
        var predictor =
            (encoded[offset].toInt() and 0xFF) or ((encoded[offset + 1].toInt() and 0xFF) shl 8)
        if (predictor >= 0x8000) predictor -= 0x10000
        var index = (encoded[offset + 2].toInt() and 0xFF).coerceIn(0, 88)

        val out = ShortArray((length - 4) * 2)
        var outPos = 0
        for (bytePos in offset + 4 until offset + length) {
            val b = encoded[bytePos].toInt() and 0xFF
            // Two nibbles per byte, low nibble first.
            for (nibble in intArrayOf(b and 0x0F, b ushr 4)) {
                val step = STEP_TABLE[index]
                val sign = nibble and 8 != 0
                val delta = nibble and 7
                var diffq = step shr 3
                if (delta and 4 != 0) diffq += step
                if (delta and 2 != 0) diffq += step shr 1
                if (delta and 1 != 0) diffq += step shr 2
                predictor = clampShort(if (sign) predictor - diffq else predictor + diffq)
                index = (index + INDEX_TABLE[nibble]).coerceIn(0, 88)
                out[outPos++] = predictor.toShort()
            }
        }
        return out
    }

    private fun clampShort(v: Int): Int = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
}
