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
    val alpn: List<String> = Hysteria2Rules.DEFAULT_ALPN,
    val pins: List<String> = emptyList(),
    val skipChecksRequested: Boolean = false,
) {
    override fun toString(): String {
        val redactedPins = "<redacted:${pins.size}>"
        return "Hysteria2Tls(alpn=$alpn, pins=$redactedPins, skipChecksRequested=$skipChecksRequested)"
    }
}

/**
 * The Hysteria2 value rules every source shares: share links, Clash, raw JSON and the editor. One home
 * each, so an imported value and a hand-typed one cannot disagree about what is valid.
 */
public object Hysteria2Rules {
    /** Xray's `udphop` interval when none is given (`infra/conf/transport_finalmask.go`, `UDPHop.Build`). */
    public const val DEFAULT_HOP_INTERVAL: Int = 30

    /** The smallest hop interval the core builds (`interval must be at least 5`). */
    public const val MIN_HOP_INTERVAL: Int = 5

    /** Gecko's largest packet size (`transport_finalmask.go`, Salamander `packetSize.To <= 2048`). */
    public const val MAX_PACKET_SIZE: Int = 2048

    /** The core replaces any ALPN with `h3` on the hysteria transport; kept for import/export fidelity. */
    public val DEFAULT_ALPN: List<String> = listOf("h3")

    private const val MAX_INTERVAL_DIGITS = 9

    /**
     * A hop interval typed as text (`hop-interval=`, Clash `hop-interval`, the editor field): blank or
     * absent is [DEFAULT_HOP_INTERVAL]; otherwise ASCII digits only (no sign, unit or inner space,
     * surrounding whitespace trimmed) and at least [MIN_HOP_INTERVAL]. Null when invalid.
     */
    public fun hopInterval(text: String?): Int? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return DEFAULT_HOP_INTERVAL
        val digits = trimmed.length <= MAX_INTERVAL_DIGITS && trimmed.all { it in '0'..'9' }
        return trimmed.takeIf { digits }?.toInt()?.takeIf { it >= MIN_HOP_INTERVAL }
    }

    /** A Gecko packet-size range the core builds: `0 < min <= max <= MAX_PACKET_SIZE`. */
    public fun geckoRangeValid(
        min: Int,
        max: Int,
    ): Boolean = min > 0 && min <= max && max <= MAX_PACKET_SIZE
}

/** Port hopping: the server listens on [ports]; the client rotates every [intervalSeconds]. */
public data class PortHopping(
    val ports: List<IntRange>,
    val intervalSeconds: Int = Hysteria2Rules.DEFAULT_HOP_INTERVAL,
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

/**
 * The BBR profiles Xray-core v26.10.10 accepts, as it compares them: lowercased, `""` meaning
 * `standard` (`infra/conf/transport_internet.go:245-252`). Anything else fails the config build with
 * `unknown bbr profile`. The single list the parser, generator and editor share. Public rather than
 * internal because those live in other modules.
 */
public val BBR_PROFILES: Set<String> = setOf("", "conservative", "standard", "aggressive")

/** [text] lowercased when it is one of [BBR_PROFILES], else null. No trimming: the core does none. */
public fun normaliseBbrProfile(text: String): String? = text.lowercase().takeIf { it in BBR_PROFILES }

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
    val bytes =
        if (unseparated.isNotEmpty() && HEX_ONLY.matches(unseparated)) {
            unseparated.takeIf { it.length == PIN_HEX_LENGTH }?.lowercase()
        } else {
            decodeBase64Pin(trimmed)?.takeIf { it.size == PIN_BYTES }?.joinToString("") { "%02x".format(it) }
        }
    return bytes?.let { PinResult.Ok(it) } ?: PinResult.Invalid
}

private fun decodeBase64Pin(text: String): ByteArray? =
    try {
        val decoder = if ('-' in text || '_' in text) Base64.getUrlDecoder() else Base64.getDecoder()
        decoder.decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }
