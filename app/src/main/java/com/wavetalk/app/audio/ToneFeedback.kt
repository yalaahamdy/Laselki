package com.wavetalk.app.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.wavetalk.app.core.WtLog

/**
 * Push-to-talk feedback: short radio-style beeps and haptic ticks.
 * Both degrade gracefully — nothing here may ever crash the app.
 */
class ToneFeedback(private val context: Context) {

    private var tone: ToneGenerator? = null

    fun pressBeep(enabled: Boolean) {
        if (!enabled) return
        playTone(ToneGenerator.TONE_PROP_BEEP, 60)
    }

    fun releaseBeep(enabled: Boolean) {
        if (!enabled) return
        playTone(ToneGenerator.TONE_PROP_ACK, 70)
    }

    fun errorBeep(enabled: Boolean) {
        if (!enabled) return
        playTone(ToneGenerator.TONE_PROP_NACK, 150)
    }

    private fun playTone(toneType: Int, durationMs: Int) {
        try {
            tone?.release()
            val gen = ToneGenerator(AudioManager.STREAM_MUSIC, 65)
            tone = gen
            gen.startTone(toneType, durationMs)
        } catch (e: Exception) {
            WtLog.w(TAG, "Tone unavailable: ${e.message}")
        }
    }

    fun vibrate(enabled: Boolean, ms: Long = 35) {
        if (!enabled) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            WtLog.w(TAG, "Vibration unavailable: ${e.message}")
        }
    }

    fun release() {
        try { tone?.release() } catch (_: Exception) {}
        tone = null
    }

    private companion object {
        const val TAG = "Feedback"
    }
}
