// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model.automation

import java.util.Locale

/**
 * A server-name hint that a server suits only one kind of network (M10-C spec §3.3).
 * Providers write it into the name, so it is read, never stored.
 */
public enum class NetworkMarker { WifiOnly, MobileOnly }

/** The kind of network the device is on right now. [Other] covers VPN, Ethernet, none. */
public enum class NetworkKind { Wifi, Mobile, Other }

private val wifiWords = listOf("WIFI", "WI-FI")
private val mobileWords = listOf("LTE", "4G", "5G", "MOBILE")

/**
 * Reads a marker from a server name: whole words, case-insensitive. A name with words from
 * both lists is unmarked, because it claims to suit both.
 */
public fun networkMarkerOf(name: String): NetworkMarker? {
    val upper = name.uppercase(Locale.ROOT)
    val wifi = wifiWords.any { upper.hasWholeWord(it) }
    val mobile = mobileWords.any { upper.hasWholeWord(it) }
    return when {
        wifi && !mobile -> NetworkMarker.WifiOnly
        mobile && !wifi -> NetworkMarker.MobileOnly
        else -> null
    }
}

// "Satellite" holds "LTE" and "Wifiland" holds "WIFI": a match counts only when the characters
// on both sides are not letters or digits.
private fun String.hasWholeWord(word: String): Boolean {
    var from = 0
    while (true) {
        val at = indexOf(word, from)
        if (at < 0) return false
        val before = if (at == 0) null else this[at - 1]
        val after = if (at + word.length == length) null else this[at + word.length]
        val startsWord = before == null || !before.isLetterOrDigit()
        val endsWord = after == null || !after.isLetterOrDigit()
        if (startsWord && endsWord) return true
        from = at + 1
    }
}

/** Whether a server with this marker may be used on [kind]. Unmarked, or an unknown network, passes. */
public fun NetworkMarker?.allowedOn(kind: NetworkKind): Boolean =
    when {
        this == null || kind == NetworkKind.Other -> true
        this == NetworkMarker.WifiOnly -> kind == NetworkKind.Wifi
        else -> kind == NetworkKind.Mobile
    }
