// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

/**
 * The WireGuard input rules the importer enforces, for the editor to apply to typed text.
 *
 * Thin public wrappers, like [Hysteria2Input]: the implementations stay with the link and Clash parsers so
 * an imported server and a hand-typed one cannot disagree about what is valid. Key and `reserved` rules are
 * in [space.getsub.core.model.WireGuardRules]; neither trims, so callers trim first (ruling D2-R7).
 */
public object WireGuardInput {
    public const val MIN_MTU: Int = MIN_WG_MTU
    public const val MAX_MTU: Int = MAX_WG_MTU
    public const val MAX_KEEPALIVE_SECONDS: Int = MAX_KEEPALIVE

    /** An IPv4/IPv6 literal with an optional prefix: what an interface address or an allowed IP may be. */
    public fun isAddress(text: String): Boolean = validLocalAddress(text)
}
