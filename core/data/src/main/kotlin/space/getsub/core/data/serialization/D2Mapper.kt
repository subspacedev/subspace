// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.serialization

import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.KcpHeader
import space.getsub.core.model.KcpObfuscation
import space.getsub.core.model.KcpUnsupported
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.WireGuardOutbound
import space.getsub.core.model.WireGuardUnsupported

// The WireGuard / HTTP / mKCP half of OutboundMapper (M9-D2), split out like Hysteria2Mapper.

internal fun WireGuardOutbound.toWireGuardDto(): OutboundDto.WireGuard =
    OutboundDto.WireGuard(
        address = address,
        port = port,
        secretKey = secretKey,
        localAddresses = localAddresses,
        peerPublicKey = peerPublicKey,
        preSharedKey = preSharedKey,
        mtu = mtu,
        reserved = reserved,
        keepAlive = keepAlive,
        allowedIps = allowedIps,
        unsupported = notConnectableReason?.name,
    )

internal fun OutboundDto.WireGuard.toWireGuardDomain(): WireGuardOutbound =
    WireGuardOutbound(
        address = address,
        port = port,
        secretKey = secretKey,
        localAddresses = localAddresses,
        peerPublicKey = peerPublicKey,
        preSharedKey = preSharedKey,
        mtu = mtu,
        reserved = reserved,
        keepAlive = keepAlive,
        allowedIps = allowedIps,
        // An unrecognised name (a newer build's reason) stays not connectable.
        notConnectableReason = unsupported?.let(::wireGuardReason),
    )

private fun wireGuardReason(name: String): WireGuardUnsupported =
    WireGuardUnsupported.entries.firstOrNull { it.name == name } ?: WireGuardUnsupported.UnsupportedMask

internal fun HttpOutbound.toHttpDto(): OutboundDto.Http =
    OutboundDto.Http(
        address = address,
        port = port,
        username = username,
        password = password,
        security = security.toDto(),
    )

internal fun OutboundDto.Http.toHttpDomain(): HttpOutbound =
    HttpOutbound(
        address = address,
        port = port,
        username = username,
        password = password,
        security = security.toDomain(),
    )

internal fun TransportOptions.Kcp.toKcpDto(): TransportDto.Kcp =
    TransportDto.Kcp(
        mtu = mtu,
        tti = tti,
        seed = obfuscation.seed,
        header = obfuscation.header?.name,
        legacyOriginal = obfuscation.legacyOriginal,
        unsupported = obfuscation.unsupported?.name,
    )

internal fun TransportDto.Kcp.toKcpDomain(): TransportOptions.Kcp {
    val knownHeader = header?.let { name -> KcpHeader.entries.firstOrNull { it.name == name } }
    val reason =
        when {
            unsupported != null ->
                KcpUnsupported.entries.firstOrNull { it.name == unsupported } ?: KcpUnsupported.UnknownMask
            // A header this build does not know must not silently become bare KCP.
            header != null && knownHeader == null -> KcpUnsupported.UnknownHeader
            else -> null
        }
    return TransportOptions.Kcp(mtu, tti, KcpObfuscation(seed, knownHeader, legacyOriginal, reason))
}
