package com.wavetalk.app

import com.wavetalk.app.audio.AdpcmCodec
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the IMA ADPCM codec: round-trip quality, framing sizes and
 * robustness on degenerate inputs.
 */
class AdpcmCodecTest {

    private fun sineFrame(phase: Double, amplitude: Double): ShortArray {
        val out = ShortArray(AdpcmCodec.FRAME_SAMPLES)
        val step = 2 * PI * 440.0 / 16000.0
        for (i in out.indices) {
            out[i] = (amplitude * sin(phase + i * step)).toInt().toShort()
        }
        return out
    }

    private fun snr(reference: ShortArray, actual: ShortArray): Double {
        var signal = 0.0
        var noise = 0.0
        for (i in reference.indices) {
            val s = reference[i].toDouble()
            val n = (reference[i] - actual[i]).toDouble()
            signal += s * s
            noise += n * n
        }
        if (noise == 0.0) return 99.0
        return 10.0 * kotlin.math.log10(signal / noise)
    }

    @Test
    fun `frame size is exactly header plus half samples`() {
        val pcm = ShortArray(AdpcmCodec.FRAME_SAMPLES) { 0 }
        val encoded = AdpcmCodec.encode(pcm)
        assertEquals(AdpcmCodec.FRAME_BYTES, encoded.size)
    }

    @Test
    fun `sine roundtrip keeps SNR above 15 dB`() {
        var phase = 0.0
        var worst = 99.0
        repeat(20) {
            val pcm = sineFrame(phase, amplitude = 12000.0)
            phase += 320 * 2 * PI * 440.0 / 16000.0
            val decoded = AdpcmCodec.decode(AdpcmCodec.encode(pcm))
            val s = snr(pcm, decoded)
            if (s < worst) worst = s
        }
        // IMA ADPCM on a pure sine typically measures 17-22 dB; 15 is the floor.
        assertTrue("SNR was $worst dB", worst > 15.0)
    }

    @Test
    fun `quiet speech-like signal survives roundtrip`() {
        var phase = 0.0
        val rnd = kotlin.random.Random(42)
        repeat(10) {
            val pcm = ShortArray(AdpcmCodec.FRAME_SAMPLES) { i ->
                val base = 2500.0 * sin(phase + i * 2 * PI * 300.0 / 16000.0)
                val noise = rnd.nextDouble(-400.0, 400.0)
                (base + noise).toInt().toShort()
            }
            phase += 320 * 2 * PI * 300.0 / 16000.0
            val decoded = AdpcmCodec.decode(AdpcmCodec.encode(pcm))
            assertTrue(snr(pcm, decoded) > 16.0)
        }
    }

    @Test
    fun `silence and DC signals do not crash or drift`() {
        val silence = ShortArray(AdpcmCodec.FRAME_SAMPLES)
        val decodedSilence = AdpcmCodec.decode(AdpcmCodec.encode(silence))
        assertTrue(decodedSilence.all { it.toInt() in -50..50 })

        val dc = ShortArray(AdpcmCodec.FRAME_SAMPLES) { 8000 }
        val decodedDc = AdpcmCodec.decode(AdpcmCodec.encode(dc))
        val avg = decodedDc.map { it.toDouble() }.average()
        assertTrue("DC average drifted: $avg", avg in 7500.0..8500.0)
    }

    @Test
    fun `independent frames decode identically whether standalone or streamed`() {
        // Every frame carries its own predictor header, so decoding one frame
        // alone must equal decoding it in a stream context.
        val pcm = sineFrame(0.0, 9000.0)
        val encoded = AdpcmCodec.encode(pcm)
        val decodedStandalone = AdpcmCodec.decode(encoded)

        val stream = encoded + encoded
        val decodedStreamFirst = AdpcmCodec.decode(stream, 0, encoded.size)
        assertTrue(decodedStandalone.contentEquals(decodedStreamFirst))
    }

    @Test
    fun `rms level meter maps quiet to zero and loud to high`() {
        val quiet = ShortArray(320)
        val levelQuiet = com.wavetalk.app.audio.AudioLevelMeter.level(quiet, 320)
        assertEquals(0f, levelQuiet, 1e-6f)

        val loud = ShortArray(320) { if (it % 2 == 0) 20000 else -20000 }
        val levelLoud = com.wavetalk.app.audio.AudioLevelMeter.level(loud, 320)
        assertTrue(levelLoud > 0.9f)
    }
}
