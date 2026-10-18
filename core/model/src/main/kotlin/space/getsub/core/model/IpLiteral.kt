// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

/**
 * IP literals as Go's `net/netip` reads them (`ParseAddr`): no zone, no leading zero in an IPv4 octet,
 * hex groups of one to four digits, at most one `::`, and an optional dotted IPv4 tail in IPv6.
 * No resolver and no `InetAddress`, which would accept forms the core refuses.
 */
internal object IpLiteral {
    private const val V4_OCTETS = 4
    private const val V6_GROUPS = 8
    private const val MAX_OCTET = 255
    private const val MAX_OCTET_DIGITS = 3
    private const val MAX_GROUP_DIGITS = 4

    fun isV4(text: String): Boolean {
        val parts = text.split('.')
        return parts.size == V4_OCTETS && parts.all(::octet)
    }

    private fun octet(part: String): Boolean =
        part.length in 1..MAX_OCTET_DIGITS &&
            part.all { it in '0'..'9' } &&
            (part.length == 1 || part[0] != '0') &&
            part.toInt() <= MAX_OCTET

    @Suppress("ReturnCount")
    fun isV6(text: String): Boolean {
        if (text.isEmpty() || ':' !in text) return false
        val doubleColon = text.indexOf("::")
        if (doubleColon >= 0 && text.indexOf("::", doubleColon + 1) >= 0) return false
        val pieces =
            if (doubleColon >= 0) {
                listOf(text.substring(0, doubleColon), text.substring(doubleColon + 2))
                    .filter { it.isNotEmpty() }
                    .flatMap { it.split(':') }
            } else {
                text.split(':')
            }
        // A dotted IPv4 tail stands for the last two groups.
        val tail = pieces.lastOrNull()?.takeIf { '.' in it }
        if (tail != null && !isV4(tail)) return false
        val groups = if (tail != null) pieces.dropLast(1) else pieces
        val count = groups.size + if (tail != null) 2 else 0
        val countOk = if (doubleColon >= 0) count < V6_GROUPS else count == V6_GROUPS
        return countOk && groups.all { g -> g.length in 1..MAX_GROUP_DIGITS && g.all { it.isHexDigit() } }
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || lowercaseChar() in 'a'..'f'
}
