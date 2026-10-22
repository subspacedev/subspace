// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

/**
 * Where the user's fragment and noise masks run (M9-D2 research §5.3, M9-D1 spec §4, M9-E spec §7.2). The
 * one author of the rule: `:core:xray` emits a mask only where this says it applies, and `:core:data`'s
 * report claims it only there, so the report never names a mask the tunnel will not run.
 *
 * Fragment is a TCP mask: it never applies to a UDP-only dial (Hysteria2's QUIC, WireGuard, mKCP).
 *
 * Noise is a UDP mask and applies only to UDP-based servers: Hysteria2, WireGuard, and VLESS, VMess or
 * Trojan over mKCP. On every TCP-carried stream (tcp/raw, ws, grpc, httpupgrade, xhttp) the core builds no
 * UDP socket for the mask to run on, and the research note
 * `docs/agent/research/2026-10-18-m9e-noise-on-tcp-transports.md` (Xray-core `finalmask.go:57-98`) found
 * it has no effect there, so offering it would only claim a mask the tunnel does not run.
 *
 * Shadowsocks and SOCKS are excluded by the owner's decision of 2026-10-18, even though the core would send
 * the mask on their UDP relay: Subspace dials them over TCP, so the user's noise does not apply to them.
 * The HTTP proxy outbound (CONNECT, TCP only) never carries it either.
 *
 * This governs only the noise the user adds. A raw config's own noise mask is never removed.
 */
public object ObfuscationApplicability {
    public fun fragmentApplies(outbound: Outbound): Boolean =
        when (outbound) {
            is Hysteria2Outbound, is WireGuardOutbound -> false
            else -> outbound.stream()?.let { !isKcpNetwork(it.network) } ?: true
        }

    public fun noiseApplies(outbound: Outbound): Boolean =
        when (outbound) {
            is Hysteria2Outbound, is WireGuardOutbound -> true
            is VlessOutbound, is VmessOutbound, is TrojanOutbound ->
                outbound.stream()?.let { isKcpNetwork(it.network) } ?: false
            else -> false
        }

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
