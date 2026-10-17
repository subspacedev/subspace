// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

/**
 * The Hysteria2 input rules the importer enforces, for the editor to apply to typed text.
 *
 * Thin public wrappers: the implementations stay in `Hysteria2Finalmask.kt` so an imported link and
 * a hand-typed field cannot disagree about what is valid.
 */
public object Hysteria2Input {
    public const val MIN_HOP_INTERVAL: Int = space.getsub.core.parser.MIN_HOP_INTERVAL
    public const val DEFAULT_HOP_INTERVAL: Int = space.getsub.core.parser.DEFAULT_HOP_INTERVAL
    public const val MIN_OBFS_PASSWORD: Int = space.getsub.core.parser.MIN_OBFS_PASSWORD
    public const val MAX_PACKET_SIZE: Int = space.getsub.core.parser.MAX_PACKET_SIZE

    /** `20000-30000,40000` as ranges, or null when any item is not a valid ascending 1..65535 range. */
    public fun portList(text: String): List<IntRange>? = parsePortList(text)

    /**
     * An Xray Bandwidth string for [text], or null when invalid or under Brutal's floor. With
     * [bareIsMbps] a bare number means Mbps (the Hysteria URI meaning) and is stored as `"<n> mbps"`.
     */
    public fun bandwidth(
        text: String,
        bareIsMbps: Boolean,
    ): String? = normaliseBandwidth(text, bareIsMbps)
}
