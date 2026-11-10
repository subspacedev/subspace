// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home.launch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import space.getsub.core.model.automation.NetworkKind
import javax.inject.Inject
import javax.inject.Singleton

/** The network the device is on, read at the moment an automatic choice is made. */
interface NetworkKindReader {
    /** The transport of the underlying non-VPN network (M10-C spec §3.3). */
    fun current(): NetworkKind

    /**
     * Whether that network is metered. Gates only the launch test, never a Fastest auto-connect
     * (M10-C spec §3.1, "Mobile data").
     */
    fun isMetered(): Boolean
}

@Singleton
internal class AndroidNetworkKindReader
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : NetworkKindReader {
    private val connectivity: ConnectivityManager?
        get() = context.getSystemService(ConnectivityManager::class.java)

    override fun current(): NetworkKind {
        val caps = connectivity?.let(::underlying)
        return when {
            caps == null -> NetworkKind.Other
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.Mobile
            else -> NetworkKind.Other
        }
    }

    // On API 29+ a VPN's meteredness follows its underlying networks, so the default network's
    // answer is the right one even while our own tunnel, or another app's, is up.
    override fun isMetered(): Boolean = connectivity?.isActiveNetworkMetered ?: false

    /**
     * The non-VPN network with internet. While a VPN is the default network its own capabilities
     * say `TRANSPORT_VPN`, which tells us nothing about Wi-Fi versus mobile, so we look past it.
     * Wi-Fi is preferred over cellular when both are up, which is how Android picks the default.
     */
    // allNetworks is deprecated from API 31 in favour of a callback; this is a one-shot read at
    // choice time, and minSdk is 26, so registering a callback to read one value would be more
    // moving parts for the same answer.
    @Suppress("DEPRECATION")
    private fun underlying(cm: ConnectivityManager): NetworkCapabilities? {
        val active = cm.activeNetwork?.let(cm::getNetworkCapabilities)
        if (active != null && active.isUsableNonVpn()) return active
        val candidates = cm.allNetworks.mapNotNull(cm::getNetworkCapabilities).filter { it.isUsableNonVpn() }
        return candidates.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            ?: candidates.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
            ?: candidates.firstOrNull()
    }

    private fun NetworkCapabilities.isUsableNonVpn(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
}
