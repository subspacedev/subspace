// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.serialization

import space.getsub.core.model.Hysteria2Congestion
import space.getsub.core.model.Hysteria2Obfuscation
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Tls
import space.getsub.core.model.Hysteria2Unsupported
import space.getsub.core.model.PortHopping

// The Hysteria2 half of OutboundMapper, split out to keep that file's function count down.

internal fun Hysteria2Outbound.toHysteria2Dto(): OutboundDto.Hysteria2 =
    OutboundDto.Hysteria2(
        address = address,
        port = port,
        password = password,
        tls = Hysteria2TlsDto(tls.serverName, tls.alpn, tls.pins, tls.skipChecksRequested),
        hopping = hopping?.toDto(),
        obfuscation = obfuscation.toDto(),
        congestion = congestion.toDto(),
        unsupported = notConnectableReason?.name,
    )

internal fun OutboundDto.Hysteria2.toHysteria2Domain(): Hysteria2Outbound =
    Hysteria2Outbound(
        address = address,
        port = port,
        password = password,
        tls = Hysteria2Tls(tls.serverName, tls.alpn, tls.pins, tls.skipChecksRequested),
        hopping = hopping?.toDomain(),
        obfuscation = obfuscation.toDomain(),
        congestion = congestion.toDomain(),
        // An unrecognised name (a newer build's reason) stays not connectable.
        notConnectableReason = unsupported?.toReason(),
    )

private fun String.toReason() =
    Hysteria2Unsupported.entries.firstOrNull { it.name == this } ?: Hysteria2Unsupported.UnknownMask

private fun PortHopping.toDto() =
    Hysteria2HoppingDto(ports = ports.map { listOf(it.first, it.last) }, intervalSeconds = intervalSeconds)

// toOutbound() never throws: a pair that is not [from, to] (nothing we wrote) is skipped.
private fun Hysteria2HoppingDto.toDomain() =
    PortHopping(ports = ports.filter { it.size == 2 }.map { it[0]..it[1] }, intervalSeconds = intervalSeconds)

private fun Hysteria2Obfuscation.toDto(): Hysteria2ObfuscationDto =
    when (this) {
        is Hysteria2Obfuscation.None -> Hysteria2ObfuscationDto.None
        is Hysteria2Obfuscation.Salamander -> Hysteria2ObfuscationDto.Salamander(password)
        is Hysteria2Obfuscation.Gecko -> Hysteria2ObfuscationDto.Gecko(password, minPacket, maxPacket)
    }

private fun Hysteria2ObfuscationDto.toDomain(): Hysteria2Obfuscation =
    when (this) {
        is Hysteria2ObfuscationDto.None -> Hysteria2Obfuscation.None
        is Hysteria2ObfuscationDto.Salamander -> Hysteria2Obfuscation.Salamander(password)
        is Hysteria2ObfuscationDto.Gecko -> Hysteria2Obfuscation.Gecko(password, minPacket, maxPacket)
    }

private fun Hysteria2Congestion.toDto(): Hysteria2CongestionDto =
    when (this) {
        is Hysteria2Congestion.Default -> Hysteria2CongestionDto.Default
        is Hysteria2Congestion.Bbr -> Hysteria2CongestionDto.Bbr(profile)
        is Hysteria2Congestion.Brutal -> Hysteria2CongestionDto.Brutal(up, down)
    }

private fun Hysteria2CongestionDto.toDomain(): Hysteria2Congestion =
    when (this) {
        is Hysteria2CongestionDto.Default -> Hysteria2Congestion.Default
        is Hysteria2CongestionDto.Bbr -> Hysteria2Congestion.Bbr(profile)
        is Hysteria2CongestionDto.Brutal -> Hysteria2Congestion.Brutal(up, down)
    }
