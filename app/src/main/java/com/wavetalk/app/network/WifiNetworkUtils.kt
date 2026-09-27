package com.wavetalk.app.network

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Helpers to inspect the local Wi-Fi network without needing location permissions.
 *
 * We enumerate [NetworkInterface]s directly (rather than using DHCP info) which
 * works on client Wi-Fi, hotspot AP mode and Ethernet alike, and never touches
 * SSID/BSSID so no ACCESS_FINE_LOCATION is required.
 */
object WifiNetworkUtils {

    data class LocalInterface(val address: Inet4Address, val prefixLength: Short)

    /**
     * Finds the primary site-local (or carrier-NAT 100.64/10) IPv4 address of this
     * device on an active non-loopback interface.
     */
    fun findLocalInterface(): LocalInterface? {
        return try {
            val candidates = mutableListOf<LocalInterface>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in interfaces.asSequence()) {
                if (nif.isLoopback || !nif.isUp) continue
                for (ia in nif.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address && isUsableLanAddress(addr)) {
                        candidates += LocalInterface(addr, ia.networkPrefixLength)
                    }
                }
            }
            // Prefer interfaces whose name hints at wireless/eth, then any other.
            candidates.firstOrNull() ?: return null
        } catch (_: SocketException) {
            null
        }
    }

    private fun isUsableLanAddress(addr: Inet4Address): Boolean {
        val b = addr.address
        val isSiteLocal = addr.isSiteLocalAddress               // 10/8, 172.16/12, 192.168/16
        val isCarrierNat = b[0] == 100.toByte() && (b[1].toInt() and 0xC0) == 64 // 100.64/10
        return isSiteLocal || isCarrierNat
    }

    /**
     * Computes the list of broadcast targets for discovery datagrams:
     * the subnet-directed broadcast plus the global 255.255.255.255.
     */
    fun broadcastAddresses(local: LocalInterface?): List<InetAddress> {
        val result = linkedSetOf<InetAddress>()
        if (local != null) {
            val ip = local.address.address
            val ipInt = ((ip[0].toInt() and 0xFF) shl 24) or
                ((ip[1].toInt() and 0xFF) shl 16) or
                ((ip[2].toInt() and 0xFF) shl 8) or
                (ip[3].toInt() and 0xFF)
            val prefix = local.prefixLength.toInt().coerceIn(0, 32)
            val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
            val broadcastInt = ipInt or mask.inv()
            result += intToInet4(broadcastInt)
            // Some routers report odd prefixes; a /24 broadcast guess improves robustness.
            if (prefix != 24) {
                result += intToInet4(ipInt or 0x000000FF)
            }
        }
        result += InetAddress.getByName("255.255.255.255")
        return result.toList()
    }

    private fun intToInet4(value: Int): Inet4Address {
        val bytes = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
        return Inet4Address.getByAddress(bytes) as Inet4Address
    }

    /**
     * Acquires a multicast lock. Required on many devices to *receive* multicast
     * (and sometimes broadcast) datagrams while Wi-Fi is active.
     */
    fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.createMulticastLock("wavetalk-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            null
        }
    }
}
