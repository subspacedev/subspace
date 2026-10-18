// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import java.util.Base64

/** Why a WireGuard source parsed but cannot be connected to. */
public enum class WireGuardUnsupported { MultiplePeers, InvalidKey, InvalidReserved, UnsupportedMask, InvalidMtu }

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

    private fun String.toByteValueOrNull(): Int? =
        takeIf { length in 1..MAX_BYTE_DIGITS && all { it in '0'..'9' } }?.toInt()?.takeIf { it <= MAX_BYTE }
}
