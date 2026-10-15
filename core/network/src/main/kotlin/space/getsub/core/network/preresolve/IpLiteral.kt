// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network.preresolve

import java.net.InetAddress

private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")
private val IPV6_CHARS = Regex("""^[0-9A-Fa-f:.]+$""")

/**
 * Strict IP literals for the bootstrap address and the "already an IP" check (M9-C spec §1.2, §2.3).
 * Never performs a DNS lookup: [InetAddress.getByName] is only reached with a string that is already
 * a well-formed literal, which it parses without resolving.
 */
public object IpLiteral {
    public fun parse(raw: String): InetAddress? {
        val s = raw.trim()
        return when {
            IPV4.matches(s) -> InetAddress.getByName(s)
            ':' in s && IPV6_CHARS.matches(s) -> runCatching { InetAddress.getByName(s) }.getOrNull()
            else -> null
        }
    }

    public fun isLiteral(raw: String): Boolean = parse(raw) != null
}
