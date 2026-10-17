// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import java.util.Base64

// ARCHITECTURE.md §5.6: passwords, obfuscation passwords and certificate pins are secrets. Every
// type here that holds one overrides toString() so a stray `"$outbound"` cannot print it.

/**
 * TLS settings of a Hysteria2 server.
 *
 * No fingerprint field: Xray ignores `fingerprint` on the hysteria transport.
 *
 * @property pins SHA-256 certificate pins as 64 lowercase hex characters (see [normalisePin]).
 * @property skipChecksRequested the source asked to skip certificate checks (`insecure=1`).
 *   Recorded so the editor can say it was ignored, never emitted (see [Security.Tls.allowInsecure]).
 */
public data class Hysteria2Tls(
    val serverName: String,
    val alpn: List<String> = listOf("h3"),
    val pins: List<String> = emptyList(),
    val skipChecksRequested: Boolean = false,
) {
    override fun toString(): String =
        "Hysteria2Tls(alpn=$alpn, pins=<redacted:${pins.size}>, skipChecksRequested=$skipChecksRequested)"
}

/** Port hopping: the server listens on [ports]; the client rotates every [intervalSeconds]. */
public data class PortHopping(
    val ports: List<IntRange>,
    val intervalSeconds: Int = 30,
)

public sealed interface Hysteria2Obfuscation {
    public data object None : Hysteria2Obfuscation

    public data class Salamander(
        val password: String,
    ) : Hysteria2Obfuscation {
        override fun toString(): String = "Salamander(password=<redacted>)"
    }

    public data class Gecko(
        val password: String,
        val minPacket: Int,
        val maxPacket: Int,
    ) : Hysteria2Obfuscation {
        override fun toString(): String = "Gecko(password=<redacted>, minPacket=$minPacket, maxPacket=$maxPacket)"
    }
}

public sealed interface Hysteria2Congestion {
    public data object Default : Hysteria2Congestion

    public data class Bbr(
        val profile: String = "",
    ) : Hysteria2Congestion

    /** [up] and [down] are Xray Bandwidth strings, e.g. `100 mbps`. */
    public data class Brutal(
        val up: String,
        val down: String,
    ) : Hysteria2Congestion
}

/** Why a Hysteria2 link parsed but cannot be connected to. */
public enum class Hysteria2Unsupported { UnknownMask, UnknownCongestion }

public sealed interface PinResult {
    public data class Ok(
        val hex: String,
    ) : PinResult {
        override fun toString(): String = "Ok(hex=<redacted>)"
    }

    public data object Invalid : PinResult
}

private const val PIN_BYTES = 32
private const val PIN_HEX_LENGTH = PIN_BYTES * 2
private val HEX_ONLY = Regex("[0-9a-fA-F]+")

/**
 * Normalises a SHA-256 certificate pin to 64 lowercase hex characters.
 *
 * Accepts hex (any case, optional `:` separators) or base64 (standard or URL-safe, padded or not).
 * Hex is tried first: a 64-character hex string is also syntactically valid base64.
 */
public fun normalisePin(text: String): PinResult {
    val trimmed = text.trim()
    val unseparated = trimmed.replace(":", "")
    if (unseparated.isNotEmpty() && HEX_ONLY.matches(unseparated)) {
        return if (unseparated.length == PIN_HEX_LENGTH) PinResult.Ok(unseparated.lowercase()) else PinResult.Invalid
    }
    val bytes =
        try {
            val decoder = if ('-' in trimmed || '_' in trimmed) Base64.getUrlDecoder() else Base64.getDecoder()
            decoder.decode(trimmed)
        } catch (_: IllegalArgumentException) {
            return PinResult.Invalid
        }
    if (bytes.size != PIN_BYTES) return PinResult.Invalid
    return PinResult.Ok(bytes.joinToString("") { "%02x".format(it) })
}
