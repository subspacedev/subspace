// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.serialization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.getsub.core.model.Hysteria2Rules
import space.getsub.core.model.WireGuardRules

/**
 * The persisted shape of [space.getsub.core.model.Outbound].
 *
 * A deliberate surrogate, not the domain model itself: M3 widened
 * `StreamSettings` once already and M8 will widen it again for fragmentation
 * and noises, and a storage format welded to the domain model turns every
 * such change into a migration. [OutboundMapper] is the only place that knows
 * both shapes.
 */
@Serializable
internal sealed interface OutboundDto {
    @Serializable
    @SerialName("vless")
    data class Vless(
        val address: String,
        val port: Int,
        val uuid: String,
        val flow: String? = null,
        val stream: StreamDto,
    ) : OutboundDto

    @Serializable
    @SerialName("vmess")
    data class Vmess(
        val address: String,
        val port: Int,
        val uuid: String,
        val alterId: Int,
        val security: String,
        val stream: StreamDto,
    ) : OutboundDto

    @Serializable
    @SerialName("trojan")
    data class Trojan(
        val address: String,
        val port: Int,
        val password: String,
        val stream: StreamDto,
    ) : OutboundDto

    @Serializable
    @SerialName("shadowsocks")
    data class Shadowsocks(
        val address: String,
        val port: Int,
        val method: String,
        val password: String,
    ) : OutboundDto

    @Serializable
    @SerialName("socks")
    data class Socks(
        val address: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
    ) : OutboundDto

    /**
     * Hysteria2. Every field after [password] has a default, so a row written by an older
     * build keeps decoding as fields are added (the same extension rule as [TransportDto.Xhttp]).
     * [unsupported] holds a `Hysteria2Unsupported` name; it is a string so a newer reason read
     * by an older build still decodes (as not connectable) rather than failing the row.
     */
    @Serializable
    @SerialName("hysteria2")
    data class Hysteria2(
        val address: String,
        val port: Int,
        val password: String,
        val tls: Hysteria2TlsDto,
        val hopping: Hysteria2HoppingDto? = null,
        val obfuscation: Hysteria2ObfuscationDto = Hysteria2ObfuscationDto.None,
        val congestion: Hysteria2CongestionDto = Hysteria2CongestionDto.Default,
        val unsupported: String? = null,
    ) : OutboundDto

    /**
     * WireGuard. Every field after [peerPublicKey] has a default (the extension rule of [Hysteria2]).
     * [unsupported] is a `WireGuardUnsupported` name, a string so a newer build's reason still decodes
     * (as [space.getsub.core.model.WireGuardUnsupported.UnsupportedMask], which is not connectable).
     * Keys are secrets: never logged (ARCHITECTURE.md §5.6).
     */
    @Serializable
    @SerialName("wireguard")
    data class WireGuard(
        val address: String,
        val port: Int,
        val secretKey: String,
        val localAddresses: List<String>,
        val peerPublicKey: String,
        val preSharedKey: String? = null,
        val mtu: Int = WireGuardRules.DEFAULT_MTU,
        val reserved: List<Int> = emptyList(),
        val keepAlive: Int? = null,
        val allowedIps: List<String> = listOf("0.0.0.0/0", "::/0"),
        val unsupported: String? = null,
    ) : OutboundDto

    /** An HTTP(S) proxy; TLS, if any, is [security]. */
    @Serializable
    @SerialName("http")
    data class Http(
        val address: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
        val security: SecurityDto = SecurityDto.None,
    ) : OutboundDto
}

@Serializable
internal data class Hysteria2TlsDto(
    val serverName: String,
    val alpn: List<String> = Hysteria2Rules.DEFAULT_ALPN,
    val pins: List<String> = emptyList(),
    val skipChecksRequested: Boolean = false,
)

/** [ports] are `[from, to]` pairs, flattened so the stored JSON needs no IntRange adapter. */
@Serializable
internal data class Hysteria2HoppingDto(
    val ports: List<List<Int>>,
    val intervalSeconds: Int = Hysteria2Rules.DEFAULT_HOP_INTERVAL,
)

@Serializable
internal sealed interface Hysteria2ObfuscationDto {
    @Serializable
    @SerialName("none")
    data object None : Hysteria2ObfuscationDto

    @Serializable
    @SerialName("salamander")
    data class Salamander(
        val password: String,
    ) : Hysteria2ObfuscationDto

    @Serializable
    @SerialName("gecko")
    data class Gecko(
        val password: String,
        val minPacket: Int,
        val maxPacket: Int,
    ) : Hysteria2ObfuscationDto
}

@Serializable
internal sealed interface Hysteria2CongestionDto {
    @Serializable
    @SerialName("default")
    data object Default : Hysteria2CongestionDto

    @Serializable
    @SerialName("bbr")
    data class Bbr(
        val profile: String = "",
    ) : Hysteria2CongestionDto

    @Serializable
    @SerialName("brutal")
    data class Brutal(
        val up: String,
        val down: String,
    ) : Hysteria2CongestionDto
}

@Serializable
internal data class StreamDto(
    val network: String,
    val security: SecurityDto,
    val transport: TransportDto = TransportDto.None,
)

@Serializable
internal sealed interface SecurityDto {
    @Serializable
    @SerialName("none")
    data object None : SecurityDto

    @Serializable
    @SerialName("reality")
    data class Reality(
        val serverName: String,
        val publicKey: String,
        val shortId: String,
        val fingerprint: String,
        val spiderX: String,
    ) : SecurityDto

    @Serializable
    @SerialName("tls")
    data class Tls(
        val serverName: String,
        val fingerprint: String,
        val allowInsecure: Boolean,
    ) : SecurityDto
}

@Serializable
internal sealed interface TransportDto {
    @Serializable
    @SerialName("none")
    data object None : TransportDto

    @Serializable
    @SerialName("ws")
    data class WebSocket(
        val path: String,
        val headers: Map<String, String>,
    ) : TransportDto

    @Serializable
    @SerialName("grpc")
    data class Grpc(
        val serviceName: String,
    ) : TransportDto

    /**
     * XHTTP.
     *
     * [host] and [mode] are nullable and defaulted, so a row written before this
     * variant existed is not the concern — no such row can exist, since nothing
     * could produce one. They are nullable because null and `""` mean different
     * things to Xray: an absent host dials with the destination address, an empty
     * one is a literal empty header. See
     * [space.getsub.core.model.TransportOptions.Xhttp].
     */
    @Serializable
    @SerialName("xhttp")
    data class Xhttp(
        val path: String,
        val host: String? = null,
        val mode: String? = null,
    ) : TransportDto

    /**
     * mKCP. Flattened from `Kcp` + `KcpObfuscation`; [header] and [unsupported] are `KcpHeader` /
     * `KcpUnsupported` names, strings so a newer build's value still decodes (an unknown one reads as
     * no header with `UnknownHeader`, or as `UnknownMask`, both not connectable). [seed] is a secret.
     */
    @Serializable
    @SerialName("kcp")
    data class Kcp(
        val mtu: Int? = null,
        val tti: Int? = null,
        val seed: String? = null,
        val header: String? = null,
        val legacyOriginal: Boolean = false,
        val unsupported: String? = null,
    ) : TransportDto

    @Serializable
    @SerialName("httpupgrade")
    data class HttpUpgrade(
        val host: String = "",
        val path: String = "/",
    ) : TransportDto
}
