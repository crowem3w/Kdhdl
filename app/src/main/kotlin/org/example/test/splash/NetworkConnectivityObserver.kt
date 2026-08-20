package org.example.test.splash

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reports whether the device currently has *usable* internet — not just a radio link.
 *
 * This exists specifically so the splash screen's milestone dots can react to a dropped
 * connection immediately. Waiting on an actual API call to time out (OkHttp's default is
 * ~10s) is far too slow for a per-dot animation slice that's often under a second; by then
 * the dot has already finished its timer and moved on, which is exactly the bug this fixes.
 * [ConnectivityManager]'s callback, by contrast, flips within milliseconds of the OS
 * detecting a change.
 *
 * Uses [NetworkCapabilities.NET_CAPABILITY_VALIDATED] rather than just "has a network" —
 * a Wi-Fi connection with no working internet behind it (dead router, captive portal) is
 * treated the same as no connection at all, matching "no internet" from the user's
 * perspective rather than just "radio is on".
 */
class NetworkConnectivityObserver(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isConnected = MutableStateFlow(currentlyConnected())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var registered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isConnected.value = currentlyConnected()
        }

        override fun onLost(network: Network) {
            _isConnected.value = currentlyConnected()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _isConnected.value = currentlyConnected()
        }

        override fun onUnavailable() {
            _isConnected.value = currentlyConnected()
        }
    }

    fun start() {
        if (registered) return
        registered = true
        _isConnected.value = currentlyConnected()
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }

    private fun currentlyConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
