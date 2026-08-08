package de.kettenblatt.prep

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the phone is on a connection it is fair to spend without asking.
 *
 * Preparing a route is a handful of calls to a public Valhalla and, if the rider
 * asks for it, tens of megabytes of tiles. Doing that unbidden on a mobile
 * allowance is not a favour.
 */
object Network {

    /**
     * True on a connection that is not metered and actually reaches the internet.
     *
     * NOT_METERED is the right question rather than "is this wifi": a phone
     * hotspot is wifi and is usually metered, and a tether the carrier does not
     * count is not wifi at all. VALIDATED is asked too, because a captive-portal
     * wifi answers every request with a login page -- which would arrive here as
     * a nonsensical map match rather than as no network at all.
     */
    fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
