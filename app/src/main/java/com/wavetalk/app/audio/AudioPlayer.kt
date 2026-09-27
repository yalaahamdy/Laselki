package com.wavetalk.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.wavetalk.app.core.AppConstants
import com.wavetalk.app.core.WtLog
import kotlin.concurrent.thread

/**
 * Plays incoming ADPCM frames through a low-latency streaming AudioTrack.
 *
 * - Speaker media routing (USAGE_MEDIA + CONTENT_TYPE_SPEECH) so the walkie
 *   sounds through the loudspeaker like a real radio.
 * - Blocking writes pace playback in real time; jitter buffer absorbs network
 *   variance and synthesizes silence on underruns.
 * - Level of the actually-played audio is reported for the UI waveform.
 */
class AudioPlayer(
    private val onLevel: (level: Float) -> Unit,
) {

    val jitter = JitterBuffer()

    @Volatile private var running = false
    private var track: AudioTrack? = null

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            AppConstants.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            WtLog.e(TAG, "AudioTrack unavailable (minBufferSize=$minBuf)")
            return
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(AppConstants.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val t = try {
            AudioTrack(
                attributes, format,
                maxOf(minBuf * 2, AppConstants.FRAME_SAMPLES * 8),
                AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        } catch (e: Exception) {
            WtLog.e(TAG, "AudioTrack creation failed", e)
            return
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            WtLog.e(TAG, "AudioTrack failed to initialize")
            return
        }
        track = t
        running = true
        thread(name = "wavetalk-playout") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val silence = ShortArray(AppConstants.FRAME_SAMPLES)
            try {
                t.play()
                var healthyStreak = 0
                while (running) {
                    val frame = if (jitter.readyToPlay() || jitter.depth > 0) {
                        jitter.poll(AppConstants.FRAME_INTERVAL_MS)
                    } else null
                    val pcm = if (frame != null) {
                        healthyStreak++
                        if (healthyStreak > 50) { jitter.onHealthy(); healthyStreak = 0 }
                        AdpcmCodec.decode(frame)
                    } else {
                        healthyStreak = 0
                        jitter.onUnderrun()
                        silence
                    }
                    t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                    onLevel(if (frame != null) AudioLevelMeter.level(pcm, pcm.size) else 0f)
                }
            } catch (e: Exception) {
                WtLog.e(TAG, "Playout loop error", e)
            } finally {
                try { t.stop() } catch (_: Exception) {}
                try { t.release() } catch (_: Exception) {}
            }
        }
        WtLog.i(TAG, "Playout started")
    }

    fun offerEncodedFrame(frame: ByteArray) {
        if (running) jitter.push(frame)
    }

    fun stop() {
        if (!running) return
        running = false
        jitter.clear()
        track = null // released by the playout thread
        WtLog.i(TAG, "Playout stopping")
    }

    private companion object {
        const val TAG = "AudioPlay"
    }
}
