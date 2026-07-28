package com.apexplow.hanterm.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Lightweight network probe used before opening an SSH socket.
 *
 * Manifest declares ACCESS_NETWORK_STATE for this check so a device with
 * Wi‑Fi/cell disabled gets a clear message instead of waiting out the TCP
 * connect timeout.
 */
internal object NetworkAvailability {

    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
