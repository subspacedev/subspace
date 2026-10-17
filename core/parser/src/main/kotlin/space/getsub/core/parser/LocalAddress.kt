// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MagicNumber")

package space.getsub.core.parser

private const val MAX_V4_PREFIX = 32
private const val MAX_V6_PREFIX = 128
private const val V4_OCTETS = 4
private const val V6_GROUPS = 8
private const val MAX_OCTET = 255
private const val MAX_GROUP_DIGITS = 4

/** A real IPv4/IPv6 literal with an optional prefix (0..32 / 0..128). No resolver, no `InetAddress`. */
internal fun validLocalAddress(text: String): Boolean {
    val slash = text.indexOf('/')
    val ip = if (slash >= 0) text.substring(0, slash) else text
    val prefix = if (slash >= 0) text.substring(slash + 1) else null
    val isV6 = ip.contains(':')
    val max = if (isV6) MAX_V6_PREFIX else MAX_V4_PREFIX
    val prefixOk =
        prefix == null || (prefix.length in 1..3 && prefix.all { it in '0'..'9' } && prefix.toInt() <= max)
    return prefixOk && if (isV6) validV6(ip) else validV4(ip)
}

private fun validV4(ip: String): Boolean {
    val parts = ip.split('.')
    return parts.size == V4_OCTETS &&
        parts.all { it.length in 1..3 && it.all { c -> c in '0'..'9' } && it.toInt() <= MAX_OCTET }
}

private fun validV6(ip: String): Boolean {
    val doubleColon = ip.indexOf("::")
    if (doubleColon >= 0 && ip.indexOf("::", doubleColon + 1) >= 0) return false
    val head = if (doubleColon >= 0) ip.substring(0, doubleColon) else ip
    val tail = if (doubleColon >= 0) ip.substring(doubleColon + 2) else ""
    val groups = (head.split(':') + tail.split(':')).filter { it.isNotEmpty() }
    val countOk = if (doubleColon >= 0) groups.size < V6_GROUPS else groups.size == V6_GROUPS
    // Empty pieces are only legal around "::"; without it a stray ":" leaves an empty piece.
    val emptyOk = doubleColon >= 0 || !ip.split(':').any { it.isEmpty() }
    return countOk &&
        emptyOk &&
        groups.all { it.length in 1..MAX_GROUP_DIGITS && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
}
