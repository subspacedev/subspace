// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

/**
 * The Hysteria2 input rules the importer enforces, for the editor to apply to typed text.
 *
 * Thin public wrappers: the implementations stay in `Hysteria2Finalmask.kt` so an imported link and
 * a hand-typed field cannot disagree about what is valid. The hop-interval, Gecko-range and ALPN rules
 * live in [space.getsub.core.model.Hysteria2Rules], which the editor uses directly.
 */
public object Hysteria2Input {
    public const val MIN_OBFS_PASSWORD: Int = space.getsub.core.parser.MIN_OBFS_PASSWORD

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
