// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

/**
 * An Xray `Int32Range` as this app emits it (M9-A spec §3.2). Always `0 ≤ from ≤ to`: Xray silently
 * swaps a reversed range, and this type refuses one instead so a typo is caught where it was typed.
 */
public data class XrayRange(val from: Int, val to: Int) {
    init {
        require(from in 0..to) { "range must satisfy 0 <= from <= to" }
    }

    /** `"10"` for a single value, `"50-100"` otherwise — both forms `Int32Range` parses. */
    public fun wire(): String = if (from == to) "$from" else "$from-$to"
}

/** Finalmask `fragment.packets`: the TLS ClientHello, or a range of writes counted from 1. */
public sealed interface FragmentPackets {
    public data object TlsHello : FragmentPackets

    public data class Records(val range: XrayRange) : FragmentPackets
}

/** A Finalmask `fragment` TCP mask (research note, `FragmentMask`). */
public data class FragmentSpec(
    val packets: FragmentPackets,
    val length: XrayRange,
    val delay: XrayRange,
    val maxSplit: XrayRange?,
)

/** How a fixed noise packet is written, matching Finalmask's `PraseByteSlice` types. */
public enum class NoisePacketType(public val wire: String) {
    Array("array"),
    Str("str"),
    Hex("hex"),
    Base64("base64"),
}

/** One noise datagram's content: random bytes, or a fixed packet. Finalmask has no `rand` *type*. */
public sealed interface NoisePayload {
    public data class Random(val length: XrayRange, val byteRange: XrayRange) : NoisePayload

    public data class Fixed(val type: NoisePacketType, val packet: String) : NoisePayload {
        // ARCHITECTURE.md §5.6: a pasted packet is user content.
        override fun toString(): String = "Fixed(type=$type, packet=<redacted>)"
    }
}

public data class NoiseItem(val payload: NoisePayload, val delay: XrayRange)

/** A Finalmask `noise` UDP mask. */
public data class NoiseSpec(val items: List<NoiseItem>) {
    init {
        require(items.isNotEmpty()) { "a noise mask needs at least one item" }
    }
}

/**
 * A mask plus whether it replaces a raw config's own (M9-A plan ruling R1). True exactly when the
 * user's setting is On: our rules always win; a directive never displaces what a config does itself.
 */
public data class Applied<out T>(val spec: T, val replaceOwn: Boolean)

/** What [space.getsub.core.model] hands the config writers. Null fields emit nothing. */
public data class Obfuscation(
    val fragment: Applied<FragmentSpec>?,
    val noise: Applied<NoiseSpec>?,
) {
    // Presence and replaceOwn only — never values (ARCHITECTURE.md §5.6).
    override fun toString(): String =
        "Obfuscation(fragment=${fragment.describe()}, noise=${noise.describe()})"

    private fun Applied<*>?.describe(): String = if (this == null) "none" else "present(replaceOwn=$replaceOwn)"
}
