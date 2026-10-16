// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import space.getsub.core.data.preresolve.PreResolvePolicy
import java.net.Inet6Address
import java.net.InetAddress

private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")
private val IPV6 = Regex("""^[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*$""")

/** Early feedback only (M9-C spec §1.4); the connect-time policy re-validates the bootstrap IP. */
internal fun isIpLiteral(s: String): Boolean = IPV4.matches(s.trim()) || isIpv6(s.trim())

// A string containing ':' is never resolved by DNS, so getByName only parses it.
private fun isIpv6(s: String): Boolean =
    IPV6.matches(s) && s.count { it == ':' } >= 2 &&
        runCatching { InetAddress.getByName(s) is Inet6Address }.getOrDefault(false)

/** The connect-time rule itself, so a URL that saves without an error is never ignored at connect. */
internal fun isDohUrl(s: String): Boolean = PreResolvePolicy.isUsableDohUrl(s)
