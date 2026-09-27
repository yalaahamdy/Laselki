package com.wavetalk.app.audio

import kotlin.math.min
import kotlin.math.sqrt

/** Computes a normalized 0..1 audio level (RMS-based) from PCM frames. */
object AudioLevelMeter {

    /**
     * @param pcm   frame samples
     * @param count valid sample count in [pcm]
     * @return 0..1 level, slightly amplified for UI visibility
     */
    fun level(pcm: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val v = pcm[i].toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / count)
        val normalized = rms / 32768.0
        return min(1f, (normalized * 6.0).toFloat())
    }
}
