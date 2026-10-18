// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

/**
 * Whether an outbound can carry UDP at all (ruling D2-R16). The one author of the rule, like
 * [ObfuscationApplicability]: `:core:xray` sends DNS over TCP and keeps the port-53 hijack for an outbound
 * this says cannot, and `:service` asks it before planning DNS.
 *
 * Xray's `http` outbound is CONNECT-only (M9-D2 research §2.2): a UDP packet routed to it is dropped, so a
 * plain-DNS resolver behind it never answers. Every other protocol here carries UDP (SOCKS through UDP
 * ASSOCIATE, WireGuard, Hysteria2 and mKCP natively, the rest through their mux/UDP-over-stream).
 */
public object UdpCarriage {
    public fun carriesUdp(outbound: Outbound): Boolean = outbound !is HttpOutbound
}
