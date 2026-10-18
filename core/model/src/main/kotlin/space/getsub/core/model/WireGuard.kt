// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import java.util.Base64

/** Why a WireGuard source parsed but cannot be connected to. */
public enum class WireGuardUnsupported {
    MultiplePeers,
    InvalidKey,
    InvalidReserved,
    UnsupportedMask,
    InvalidMtu,

    /** A raw config's `allowedIPs` entry the core cannot read as a prefix (ruling D2-R15). */
    InvalidAllowedIp,
}

public object WireGuardRules {
    public const val KEY_BYTES: Int = 32
    public const val RESERVED_BYTES: Int = 3
    public const val DEFAULT_MTU: Int = 1420

    /**
     * Decodes [text] under the standard or the URL-safe alphabet, padded or not (ruling D2-R2). Mixing
     * alphabets, whitespace and non-canonical padding are rejected. Null when not base64.
     */
    private fun decode(text: String): ByteArray? {
        val urlSafe = text.any { it == '-' || it == '_' }
        val standard = text.any { it == '+' || it == '/' }
        if (text.isEmpty() || (urlSafe && standard)) return null
        val decoder = if (urlSafe) Base64.getUrlDecoder() else Base64.getDecoder()
        return runCatching { decoder.decode(text) }.getOrNull()
    }

    private const val HEX_KEY_LENGTH = KEY_BYTES * 2

    /**
     * True iff [text] is 64 hex characters (any case) or decodes to exactly 32 bytes as base64 (ruling D2-R8:
     * Xray's `ParseWireGuardKey`, `infra/conf/wireguard.go`, takes both). Keys are stored as given.
     */
    public fun keyValid(text: String): Boolean =
        (text.length == HEX_KEY_LENGTH && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) ||
            decode(text)?.size == KEY_BYTES

    private const val MAX_BYTE = 255
    private const val MAX_BYTE_DIGITS = 3

    /**
     * `reserved`: blank is empty; `1,2,3` (each 0..255) or base64 of 3 bytes is three values. Anything
     * else is null (invalid).
     */
    public fun parseReserved(text: String): List<Int>? {
        val t = text.trim()
        val values =
            when {
                t.isEmpty() -> emptyList()
                t.contains(',') -> t.split(',').map { it.trim().toByteValueOrNull() ?: return null }
                else -> decode(t)?.map { it.toInt() and MAX_BYTE }
            }
        return values?.takeIf { it.isEmpty() || it.size == RESERVED_BYTES }
    }

    private const val MAX_V4_BITS = 32
    private const val MAX_V6_BITS = 128
    private const val MAX_BITS_DIGITS = 3

    /**
     * One peer `allowedIPs` entry as the core will accept it, or null (ruling D2-R15). wireguard-go reads each
     * entry with `netip.ParsePrefix` (`device/uapi.go:382`) when the tunnel starts
     * (`proxy/wireguard/client.go:322`), after `xray -test` has passed, so a bare address there fails only on
     * connect. A valid CIDR is kept as given; a bare IPv4 address gets `/32` and a bare IPv6 one `/128`;
     * anything else (a name, a zone, a leading zero, a prefix out of range) is null. Does not trim (like
     * [keyValid], D2-R7). Interface (local) addresses are a different field and keep accepting bare addresses.
     */
    public fun normaliseAllowedIp(text: String): String? {
        val slash = text.indexOf('/')
        val ip = if (slash >= 0) text.substring(0, slash) else text
        val v6 = ':' in ip
        val ipOk = if (v6) IpLiteral.isV6(ip) else IpLiteral.isV4(ip)
        val bits = if (slash >= 0) text.substring(slash + 1) else null
        val max = if (v6) MAX_V6_BITS else MAX_V4_BITS
        val bitsOk = bits == null || prefixBitsValid(bits, max)
        return when {
            !ipOk || !bitsOk -> null
            bits == null -> "$text/$max"
            else -> text
        }
    }

    /** Decimal, no sign, no leading zero, at most [max]: what `netip.ParsePrefix` takes after the slash. */
    private fun prefixBitsValid(
        bits: String,
        max: Int,
    ): Boolean =
        bits.length in 1..MAX_BITS_DIGITS &&
            bits.all { it in '0'..'9' } &&
            (bits.length == 1 || bits[0] != '0') &&
            bits.toInt() <= max

    private fun String.toByteValueOrNull(): Int? =
        takeIf { length in 1..MAX_BYTE_DIGITS && all { it in '0'..'9' } }?.toInt()?.takeIf { it <= MAX_BYTE }
}
