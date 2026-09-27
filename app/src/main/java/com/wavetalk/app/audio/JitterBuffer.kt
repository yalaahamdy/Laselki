package com.wavetalk.app.audio

import com.wavetalk.app.core.AppConstants
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Minimal adaptive jitter buffer for 20ms ADPCM audio frames.
 *
 * - Playout starts once [startThreshold] frames are buffered (low initial latency).
 * - If the buffer runs empty, callers synthesize silence and the threshold
 *   temporarily rises (adapts to network jitter).
 * - If the buffer overflows, the oldest frame is dropped (latency stays bounded).
 */
class JitterBuffer(
    private val startThreshold: Int = 3,
    private val maxDepth: Int = 8,
) {

    private val queue = LinkedBlockingDeque<ByteArray>()

    @Volatile private var extraThreshold = 0

    val depth: Int get() = queue.size

    /** True once enough frames are queued to start (or already started) playout. */
    fun readyToPlay(): Boolean = queue.size >= startThreshold + extraThreshold

    /** Pushes an encoded frame; drops the oldest frame when overfull. */
    fun push(frame: ByteArray) {
        if (!queue.offerLast(frame)) return
        while (queue.size > maxDepth) {
            queue.pollFirst() ?: break
        }
    }

    /** Polls a frame, waiting up to [timeoutMs]. Returns null on timeout. */
    fun poll(timeoutMs: Long = AppConstants.FRAME_INTERVAL_MS): ByteArray? =
        queue.pollFirst(timeoutMs, TimeUnit.MILLISECONDS)

    /** Called when an underrun happened so playout waits slightly longer next time. */
    fun onUnderrun() {
        extraThreshold = (extraThreshold + 1).coerceAtMost(2)
    }

    /** Called after a healthy stretch of playback to shrink latency again. */
    fun onHealthy() {
        extraThreshold = 0
    }

    fun clear() {
        queue.clear()
        extraThreshold = 0
    }
}
