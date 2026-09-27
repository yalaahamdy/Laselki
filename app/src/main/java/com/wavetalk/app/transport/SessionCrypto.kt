package com.wavetalk.app.transport

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Session key derivation and stream encryption for audio/control connections.
 *
 * Model: both sides already share the channel name (a lightweight shared
 * secret). A fresh session key is derived from channel secret + both nonces,
 * so every TCP session uses a unique AES-128-CTR keystream. This protects
 * against passive eavesdroppers on the LAN; it is not protection against an
 * active MITM who knows the channel name (documented limitation).
 */
object SessionCrypto {

    private const val KEY_DERIVATION_INFO_UP = "WT-UP"
    private const val KEY_DERIVATION_INFO_DOWN = "WT-DOWN"

    data class SessionKeys(val aesKey: ByteArray, val ivUp: ByteArray, val ivDown: ByteArray)

    fun channelSecret(channel: String): ByteArray =
        sha256("wavetalk-v1:${channel.trim().lowercase()}".toByteArray(Charsets.UTF_8))

    fun deriveSessionKeys(channel: String, clientNonce: ByteArray, serverNonce: ByteArray): SessionKeys {
        val base = sha256(channelSecret(channel) + clientNonce + serverNonce)
        val ivUp = sha256(base + KEY_DERIVATION_INFO_UP.toByteArray()).copyOfRange(0, 16)
        val ivDown = sha256(base + KEY_DERIVATION_INFO_DOWN.toByteArray()).copyOfRange(0, 16)
        return SessionKeys(base.copyOfRange(0, 16), ivUp, ivDown)
    }

    /**
     * Directional AES-128-CTR keystream. A cipher instance is single-direction
     * and used sequentially from one thread — CTR counter state advances across
     * update() calls, so encryption/decryption order must match on both sides
     * (guaranteed by TCP ordering).
     */
    class StreamCipher(key: ByteArray, iv: ByteArray) {
        private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding").also {
            it.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        }

        fun process(data: ByteArray): ByteArray =
            if (data.isEmpty()) data else cipher.update(data)
    }

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)
}
