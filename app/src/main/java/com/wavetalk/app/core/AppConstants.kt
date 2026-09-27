package com.wavetalk.app.core

/**
 * Central protocol & application constants for WaveTalk.
 *
 * Wire protocol summary:
 *  - Discovery: UDP datagrams broadcast on [DISCOVERY_PORT] (announce/leave/talk events).
 *  - Audio:     TCP streams on [AUDIO_PORT_BASE]..+14 carrying encrypted 20ms ADPCM frames.
 */
object AppConstants {

    // ---------- Discovery (UDP) ----------
    const val DISCOVERY_PORT = 48895

    /** Interval between presence announcements while the app is active. */
    const val ANNOUNCE_INTERVAL_MS = 1_200L

    /** A device is considered offline if no announcement was seen for this long. */
    const val PRESENCE_OFFLINE_MS = 4_000L

    /** A device entry is evicted completely after this long without contact. */
    const val PRESENCE_EVICT_MS = 10_000L

    /** Fast burst of announcements after a network change or app start. */
    const val ANNOUNCE_BURST_COUNT = 4
    const val ANNOUNCE_BURST_INTERVAL_MS = 250L

    // ---------- Audio (TCP) ----------
    const val AUDIO_PORT_BASE = 48896
    const val AUDIO_PORT_SPAN = 15

    /** Samples per audio frame: 20ms of 16kHz mono PCM. */
    const val FRAME_SAMPLES = 320
    const val SAMPLE_RATE = 16_000

    /** Audio frame cadence in milliseconds. */
    const val FRAME_INTERVAL_MS = 20L

    // ---------- Limits & misc ----------
    const val MAX_DISCOVERY_PACKET_BYTES = 600
    const val MAX_FRAME_BYTES = 4_096

    /** TCP read timeout after handshake; keepalive pings fire every [PING_INTERVAL_MS]. */
    const val PING_INTERVAL_MS = 4_000L
    const val SOCKET_READ_TIMEOUT_MS = 12_000
    const val HANDSHAKE_TIMEOUT_MS = 2_500
    const val CONNECT_TIMEOUT_MS = 1_500

    /** Wire protocol version — bumped on incompatible changes. */
    const val PROTOCOL_VERSION = 1
}
