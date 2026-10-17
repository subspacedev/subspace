// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

// ARCHITECTURE.md §5.6: the mKCP seed is a shared secret; every type holding one redacts it.

/** The mKCP packet headers Xray's `mkcp-legacy` mask knows, by their wire name. */
public enum class KcpHeader(
    public val wireName: String,
) {
    DNS("dns"),
    DTLS("dtls"),
    SRTP("srtp"),
    UTP("utp"),
    WECHAT("wechat"),
    WIREGUARD("wireguard"),
}

/** Why an mKCP source parsed but cannot be connected to. */
public enum class KcpUnsupported {
    /** `headerType=http`: no such mask in the core. */
    HttpHeader,

    /** A header name this build does not know. */
    UnknownHeader,
}

/**
 * mKCP obfuscation as the sources express it. Not part of `kcpSettings` in the generated config:
 * Xray v26.10.10 carries it as `finalmask.udp` masks (see [KcpRules.masks]).
 *
 * @property legacyOriginal the source asked for the classic "original" packet obfuscation explicitly
 *   (a raw config with an empty `mkcp-legacy` mask). A link with no seed and no header is bare KCP.
 * @property unsupported non-null when the link named a header this build cannot write.
 */
public data class KcpObfuscation(
    val seed: String?,
    val header: KcpHeader?,
    val legacyOriginal: Boolean,
    val unsupported: KcpUnsupported? = null,
) {
    override fun toString(): String =
        "KcpObfuscation(seed=${if (seed.isNullOrEmpty()) "none" else "<redacted>"}, header=$header, " +
            "legacyOriginal=$legacyOriginal, unsupported=$unsupported)"
}

public sealed interface KcpHeaderResult {
    public data class Ok(
        val header: KcpHeader,
    ) : KcpHeaderResult

    /** `none` or blank: no header. */
    public data object None : KcpHeaderResult

    /** `http` or a name this build does not know. */
    public data object Unsupported : KcpHeaderResult
}

/** One `mkcp-legacy` mask, typed; `:core:xray` serialises these in list order. */
public sealed interface KcpMask {
    /** `{}`: the classic "original" obfuscation. */
    public data object Original : KcpMask

    /** `{"value": seed}` */
    public data class Seed(
        val value: String,
    ) : KcpMask {
        override fun toString(): String = "Seed(value=<redacted>)"
    }

    /** `{"header": h}` */
    public data class Header(
        val header: KcpHeader,
    ) : KcpMask
}

public object KcpRules {
    /** A link/Clash header name: case-insensitive, `wechat-video` is `wechat`, blank and `none` are no header. */
    public fun headerFromLinkName(name: String?): KcpHeaderResult {
        val n = name?.trim()?.lowercase().orEmpty()
        return when {
            n.isEmpty() || n == "none" -> KcpHeaderResult.None
            n == "wechat-video" -> KcpHeaderResult.Ok(KcpHeader.WECHAT)
            else ->
                KcpHeader.entries.firstOrNull { it.wireName == n }?.let(KcpHeaderResult::Ok)
                    ?: KcpHeaderResult.Unsupported
        }
    }

    /**
     * The `finalmask.udp` masks for [o], in JSON order: **[seed or original, header]**. JSON index 0 is
     * innermost and the legacy wire format is `[header][aead(payload)]`, so the seed mask precedes the
     * header (research §3.3, wire capture). A header with no seed gets `original` first (ruling D2-R3).
     * Bare KCP (no seed, no header, not [KcpObfuscation.legacyOriginal]) has no masks.
     */
    public fun masks(o: KcpObfuscation): List<KcpMask> {
        val seed = o.seed?.takeIf { it.isNotEmpty() }
        val first: KcpMask? =
            when {
                seed != null -> KcpMask.Seed(seed)
                o.legacyOriginal || o.header != null -> KcpMask.Original
                else -> null
            }
        return listOfNotNull(first, o.header?.let(KcpMask::Header))
    }

    /** Non-null when [kcp] cannot be written: the connectable gate reads this. */
    public fun notConnectableReason(kcp: TransportOptions.Kcp): KcpUnsupported? = kcp.obfuscation.unsupported
}
