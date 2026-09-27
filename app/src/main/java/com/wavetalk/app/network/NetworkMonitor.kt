package com.wavetalk.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Observes Wi-Fi connectivity via ConnectivityManager network callbacks.
 * Emits true whenever a Wi-Fi network is available and validated locally.
 */
class NetworkMonitor(context: Context, private val scope: CoroutineScope) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _wifiUp = MutableStateFlow(false)
    val wifiUp: Flow<Boolean> = _wifiUp

    /** Signalled whenever the Wi-Fi network changes (up/down) so listeners can resync. */
    private val _events = MutableStateFlow(0)
    val changeEvents: Flow<Int> = _events

    private val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _wifiUp.value = true
            _events.value += 1
        }

        override fun onLost(network: Network) {
            // Re-check: another Wi-Fi network may still be active.
            scope.launch { reevaluate() }
            _events.value += 1
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val up = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (up != _wifiUp.value) {
                _wifiUp.value = up
                _events.value += 1
            }
        }
    }

    fun start() {
        reevaluate()
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
            // Very old or restricted builds: poll-less fallback keeps app usable.
        }
    }

    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    private fun reevaluate() {
        val networks = connectivityManager.allNetworks
        val up = networks.any { n ->
            val caps = connectivityManager.getNetworkCapabilities(n)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (up != _wifiUp.value) {
            _wifiUp.value = up
            _events.value += 1
        }
    }
}
