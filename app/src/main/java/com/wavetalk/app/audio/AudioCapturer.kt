package com.wavetalk.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.wavetalk.app.core.AppConstants
import com.wavetalk.app.core.WtLog
import kotlin.concurrent.thread

/**
 * Captures 20ms mono 16-bit PCM frames from the microphone on a dedicated
 * high-priority thread. Uses the VOICE_COMMUNICATION source so the platform's
 * acoustic echo cancellation / noise suppression can engage.
 *
 * Frame callback runs on the capture thread — keep it fast and non-blocking.
 */
class AudioCapturer(
    private val gainProvider: () -> Float,
    private val onFrame: (pcm: ShortArray, count: Int, level: Float) -> Unit,
    private val onError: (message: String) -> Unit,
) {

    @Volatile private var running = false
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission") // Permission is verified before start().
    fun start(hasMicPermission: Boolean) {
        if (running) return
        if (!hasMicPermission) {
            onError("Microphone permission missing")
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            AppConstants.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onError("AudioRecord unavailable (minBufferSize=$minBuf)")
            return
        }
        val buffer = maxOf(minBuf * 4, AppConstants.FRAME_SAMPLES * 8)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AppConstants.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buffer,
            )
        } catch (e: Exception) {
            onError("AudioRecord creation failed: ${e.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onError("AudioRecord failed to initialize")
            return
        }
        record = rec
        running = true
        thread(name = "wavetalk-capture") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val frame = ShortArray(AppConstants.FRAME_SAMPLES)
            try {
                rec.startRecording()
                while (running) {
                    val read = rec.read(frame, 0, frame.size)
                    if (read <= 0) continue
                    val gain = gainProvider()
                    if (gain != 1.0f) {
                        for (i in 0 until read) {
                            val v = (frame[i] * gain).toInt()
                            frame[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }
                    }
                    onFrame(frame, read, AudioLevelMeter.level(frame, read))
                }
            } catch (e: Exception) {
                if (running) {
                    WtLog.e(TAG, "Capture loop error", e)
                    onError("Capture error: ${e.message}")
                }
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }
        WtLog.i(TAG, "Capture started")
    }

    fun stop() {
        if (!running) return
        running = false
        record = null // released by the capture thread
        WtLog.i(TAG, "Capture stopping")
    }

    private companion object {
        const val TAG = "AudioCapture"
    }
}
