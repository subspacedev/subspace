// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.Hysteria2Congestion
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Unsupported
import space.getsub.core.model.KcpRules
import space.getsub.core.model.KcpUnsupported
import space.getsub.core.model.Outbound
import space.getsub.core.model.Security
import space.getsub.core.model.ShadowsocksOutbound
import space.getsub.core.model.ShadowsocksRefusal
import space.getsub.core.model.SocksOutbound
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.TrojanOutbound
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.VmessOutbound
import space.getsub.core.model.WireGuardOutbound
import space.getsub.core.model.WireGuardRules
import space.getsub.core.model.WireGuardUnsupported
import space.getsub.core.model.isHttpUpgradeNetwork
import space.getsub.core.model.isKcpNetwork
import space.getsub.core.model.normaliseBbrProfile
import space.getsub.core.model.shadowsocksCoreRefusal

/**
 * The fixed name of what makes [outbound] a config the core would refuse, or that would connect to
 * nothing, or null when [XrayConfigGenerator] can write it. Every name is a literal because the UI shows it
 * and the core's own error would quote secrets (ARCHITECTURE.md §5.6, M9-D2a spec §0).
 *
 * Mirrors `StoredProfile.connectable` in `:core:data`, which cannot depend on this module; this side also
 * guards rows the parsers never produce (an editor row, a stale stored row), so a refusal here is never
 * reached through a misbehaving core build.
 */
internal fun typedRefusal(outbound: Outbound): String? =
    when (outbound) {
        is VlessOutbound -> outbound.stream.refusal()
        is VmessOutbound -> outbound.stream.refusal()
        is TrojanOutbound -> outbound.stream.refusal()
        is SocksOutbound -> null
        // HTTP over REALITY is not a combination the core builds; plain and TLS are.
        is HttpOutbound -> "HTTP proxy over REALITY".takeIf { outbound.security is Security.Reality }
        is WireGuardOutbound -> outbound.refusal()
        is Hysteria2Outbound -> outbound.refusal()
        is ShadowsocksOutbound ->
            when (shadowsocksCoreRefusal(outbound.method, outbound.password)) {
                null -> null
                ShadowsocksRefusal.Cipher -> "Shadowsocks cipher"
                ShadowsocksRefusal.Key -> "Shadowsocks 2022 key"
            }
    }

private const val TRANSPORT_MISMATCH = "mKCP/HTTPUpgrade transport"

/**
 * mKCP and HTTPUpgrade (M9-D2 spec §3): REALITY over either fails the core's build (`REALITY only supports
 * RAW, XHTTP and gRPC`, research §3.4), and an mKCP row the model cannot reproduce would run without the
 * obfuscation its server expects. Options of one transport under another network are never emitted (ruling
 * D2-R10: the parsers never produce them, and guessing which half is right could drop the obfuscation).
 */
private fun StreamSettings.refusal(): String? {
    val kcp = isKcpNetwork(network)
    val upgrade = isHttpUpgradeNetwork(network)
    return when {
        mismatched(kcp, upgrade) -> TRANSPORT_MISMATCH
        security is Security.Reality && kcp -> "REALITY over mKCP"
        security is Security.Reality && upgrade -> "REALITY over HTTPUpgrade"
        else -> (transport as? TransportOptions.Kcp)?.refusal()
    }
}

private fun StreamSettings.mismatched(
    kcp: Boolean,
    upgrade: Boolean,
): Boolean =
    when (transport) {
        is TransportOptions.Kcp -> !kcp
        is TransportOptions.HttpUpgrade -> !upgrade
        TransportOptions.None -> false
        else -> kcp || upgrade
    }

// `KCPConfig.Build` (infra/conf/transport_method.go:567-572) rejects mtu/tti below its floors, quoting nothing
// secret, and KcpRules adds a datagram-sized mtu ceiling; a refusal here names it before the core is involved.
private fun TransportOptions.Kcp.refusal(): String? =
    when (KcpRules.notConnectableReason(this)) {
        null -> null
        KcpUnsupported.InvalidSetting -> "mKCP settings"
        else -> "mKCP obfuscation"
    }

/**
 * A reason recorded at import, else what `WireGuardConfig.Build`/`core.New` would refuse
 * (`infra/conf/wireguard.go`, research §1.1): a key `ParseWireGuardKey` cannot read, or `reserved` that is
 * not empty or 3 bytes. Keys are checked here because the core's error is the only other place a bad one
 * surfaces, and a key never goes into a refusal.
 */
private fun WireGuardOutbound.refusal(): String? = notConnectableReason?.refusalName() ?: writtenRefusal()

private fun WireGuardUnsupported.refusalName(): String =
    when (this) {
        WireGuardUnsupported.MultiplePeers -> "WireGuard peers"
        WireGuardUnsupported.InvalidKey -> "WireGuard key"
        WireGuardUnsupported.InvalidReserved -> "WireGuard reserved"
        WireGuardUnsupported.UnsupportedMask -> "WireGuard mask"
        WireGuardUnsupported.InvalidMtu -> "WireGuard MTU"
        WireGuardUnsupported.InvalidAllowedIp -> ALLOWED_IPS
    }

private fun WireGuardOutbound.writtenRefusal(): String? {
    val keysValid =
        WireGuardRules.keyValid(secretKey) &&
            WireGuardRules.keyValid(peerPublicKey) &&
            preSharedKey.let { it.isNullOrEmpty() || WireGuardRules.keyValid(it) }
    val reservedValid =
        (reserved.isEmpty() || reserved.size == WireGuardRules.RESERVED_BYTES) && reserved.all { it in 0..BYTE_MAX }
    return when {
        !keysValid -> "WireGuard key"
        !reservedValid -> "WireGuard reserved"
        // Ruling D2-R15: wireguard-go reads each entry with netip.ParsePrefix at connect (device/uapi.go:382),
        // after `xray -test` has passed, so a bare address is refused here rather than failing silently there.
        allowedIps.any { WireGuardRules.normaliseAllowedIp(it) != it } -> ALLOWED_IPS
        else -> null
    }
}

private const val BYTE_MAX = 255
private const val ALLOWED_IPS = "WireGuard allowed IPs"

// A row whose source carried a mask or congestion this build cannot write would connect to nothing if
// generated without it (M9-D1 spec §1). A stored BBR profile outside BBR_PROFILES (an older row, an editor
// value) would fail the core's Build with `unknown bbr profile`; the profile itself is not quoted.
private fun Hysteria2Outbound.refusal(): String? =
    when {
        notConnectableReason == Hysteria2Unsupported.UnknownMask -> "Hysteria2 mask"
        notConnectableReason == Hysteria2Unsupported.UnknownCongestion -> "Hysteria2 congestion"
        (congestion as? Hysteria2Congestion.Bbr)?.let { normaliseBbrProfile(it.profile) == null } == true ->
            "Hysteria2 BBR profile"
        else -> null
    }
