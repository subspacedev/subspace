// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import java.net.InetAddress

/**
 * Whether Xray-core v26.10.10 refuses to build this outbound for lack of transport security.
 *
 * Upstream `a1bf968` (`infra/conf/xray.go` `validateOutboundTransportSecurity` /
 * `requiresTransportSecurity`, v26.10.10) rejects VLESS and Trojan when `streamSettings` names no
 * security type and the server address is not private. There is no opt-out. The core's own error
 * is what the user would otherwise see, so callers name the refusal before the core is involved.
 *
 * Mirrors upstream: only VLESS (with `encryption` empty or `none`) and Trojan are ever refused;
 * [Security.Tls] and [Security.Reality] are allowed; a VLESS `encryption` other than `none`
 * (post-quantum encryption) is allowed. [vlessEncryption] exists for that last rule: the typed
 * [VlessOutbound] carries no encryption field and always generates `"none"`, so every typed caller
 * takes the default.
 */
public fun Outbound.refusedWithoutTransportSecurity(vlessEncryption: String = "none"): Boolean {
    val stream =
        when (this) {
            is VlessOutbound -> stream.takeIf { vlessEncryption.isEmpty() || vlessEncryption == "none" }
            is TrojanOutbound -> stream
            else -> null
        }
    return stream != null && stream.security == Security.None && !isPrivateServerAddress(address)
}

/** Upstream `GetPrivateIPMatcher` rules: `common/geodata/consts.go` at v26.10.10, verbatim. */
private val PRIVATE_IPV4_CIDRS: List<Pair<Int, Int>> =
    listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.0.0.0/24",
        "192.0.2.0/24",
        "192.88.99.0/24",
        "192.168.0.0/16",
        "198.18.0.0/15",
        "198.51.100.0/24",
        "203.0.113.0/24",
        "224.0.0.0/3",
    ).map { cidr ->
        val (addr, prefix) = cidr.split('/')
        packBytes(ByteArray(IPV4_BYTES) { addr.split('.')[it].toInt().toByte() }) to prefix.toInt()
    }

/** The IPv6 rules of the same list: `::/127`, `fc00::/7`, `fe80::/10`, `ff00::/8`. */
@Suppress("MagicNumber") // The bit patterns of the four CIDRs above; named constants would obscure them.
private fun isPrivateIpv6(b: ByteArray): Boolean {
    val first = b[0].toInt() and 0xFF
    val second = b[1].toInt() and 0xFF
    val allZeroButLast = (0 until 15).all { b[it].toInt() == 0 } && (b[15].toInt() and 0xFE) == 0
    return allZeroButLast ||
        (first and 0xFE) == 0xFC ||
        (first == 0xFE && (second and 0xC0) == 0x80) ||
        first == 0xFF
}

/** Upstream `GetPrivateDomainMatcher` "Domain" rules, verbatim; the regexp rule follows below. */
private val PRIVATE_DOMAIN_SUFFIXES: List<String> =
    listOf("lan", "localdomain", "example", "invalid", "localhost", "test", "local", "home.arpa", "internal")

/** `regexp:^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$` — upstream's "Dotless domains" rule. */
private val DOTLESS_DOMAIN = Regex("^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$")

private val IPV4_LITERAL = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

private fun isPrivateServerAddress(raw: String): Boolean {
    val address = raw.trim().removeSurrounding("[", "]")
    val ipv4 = ipv4Value(address)
    return when {
        ipv4 != null -> isPrivateIpv4(ipv4)
        // A ':' means an IPv6 literal, which InetAddress parses without any lookup.
        ':' in address -> {
            val bytes = runCatching { InetAddress.getByName(address).address }.getOrNull()
            when (bytes?.size) {
                null -> false
                IPV4_BYTES -> isPrivateIpv4(packBytes(bytes))
                else -> isPrivateIpv6(bytes)
            }
        }
        else -> {
            val domain = address.lowercase().removeSuffix(".")
            DOTLESS_DOMAIN.matches(domain) || PRIVATE_DOMAIN_SUFFIXES.any { domain == it || domain.endsWith(".$it") }
        }
    }
}

private const val IPV4_BYTES = 4
private const val OCTET_MAX = 255
private const val BYTE_MASK = 0xFF
private const val IPV4_BITS = 32

/** Big-endian packing of four address bytes, the representation [PRIVATE_IPV4_CIDRS] uses. */
private fun packBytes(bytes: ByteArray): Int =
    bytes.fold(0) { acc, b ->
        (acc shl Byte.SIZE_BITS) or (b.toInt() and BYTE_MASK)
    }

private fun ipv4Value(address: String): Int? =
    IPV4_LITERAL
        .matchEntire(address)
        ?.groupValues
        ?.drop(1)
        ?.map { it.toInt() }
        ?.takeIf { parts -> parts.all { it <= OCTET_MAX } }
        ?.let { parts -> packBytes(ByteArray(IPV4_BYTES) { parts[it].toByte() }) }

private fun isPrivateIpv4(value: Int): Boolean =
    PRIVATE_IPV4_CIDRS.any { (network, prefix) ->
        val mask = if (prefix == 0) 0 else -1 shl (IPV4_BITS - prefix)
        (value and mask) == (network and mask)
    }
