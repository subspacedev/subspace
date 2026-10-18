// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

/**
 * Where the user's fragment and noise masks run (M9-D2 research §5.3, M9-D1 spec §4). The one author of
 * the rule: `:core:xray` emits a mask only where this says it applies, and `:core:data`'s report claims it
 * only there, so the report never names a mask the tunnel will not run.
 *
 * Fragment is a TCP mask: it never applies to a UDP-only dial (Hysteria2's QUIC, WireGuard, mKCP).
 * Noise is a UDP mask: it applies to those, and never to the HTTP proxy outbound (CONNECT, TCP only) or
 * an HTTPUpgrade stream. Every other protocol and transport keeps the M9-A behaviour of both.
 */
public object ObfuscationApplicability {
    public fun fragmentApplies(outbound: Outbound): Boolean =
        when (outbound) {
            is Hysteria2Outbound, is WireGuardOutbound -> false
            else -> outbound.stream()?.let { !isKcpNetwork(it.network) } ?: true
        }

    public fun noiseApplies(outbound: Outbound): Boolean =
        when (outbound) {
            is HttpOutbound -> false
            else -> outbound.stream()?.let { !isHttpUpgradeNetwork(it.network) } ?: true
        }

    /** `kcp` and `mkcp` name the same transport in Xray-core (`infra/conf/transport_internet.go:22-23`). */
    public fun isKcpNetwork(network: String): Boolean = network.lowercase().let { it == "kcp" || it == "mkcp" }

    public fun isHttpUpgradeNetwork(network: String): Boolean = network.equals("httpupgrade", ignoreCase = true)

    private fun Outbound.stream(): StreamSettings? =
        when (this) {
            is VlessOutbound -> stream
            is VmessOutbound -> stream
            is TrojanOutbound -> stream
            else -> null
        }
}

/** This with every mask [ObfuscationApplicability] says does not run on [outbound] removed. */
public fun Obfuscation.applicableTo(outbound: Outbound): Obfuscation =
    Obfuscation(
        fragment = fragment?.takeIf { ObfuscationApplicability.fragmentApplies(outbound) },
        noise = noise?.takeIf { ObfuscationApplicability.noiseApplies(outbound) },
    )
