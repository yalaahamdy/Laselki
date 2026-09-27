package com.wavetalk.app.core

import android.util.Log

/**
 * Tiny logging facade — single tag prefix, easy to silence for release.
 */
object WtLog {
    private const val PREFIX = "WaveTalk."

    fun d(tag: String, message: String) = Log.d(PREFIX + tag, message)
    fun i(tag: String, message: String) = Log.i(PREFIX + tag, message)
    fun w(tag: String, message: String) = Log.w(PREFIX + tag, message)
    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.e(PREFIX + tag, message, error) else Log.e(PREFIX + tag, message)
    }
}
