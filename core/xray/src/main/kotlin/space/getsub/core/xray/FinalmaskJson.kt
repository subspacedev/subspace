// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.Hysteria2Congestion
import space.getsub.core.model.Hysteria2Obfuscation
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.KcpMask
import space.getsub.core.model.NoisePacketType
import space.getsub.core.model.NoisePayload
import space.getsub.core.model.NoiseSpec
import space.getsub.core.model.Obfuscation

/**
 * The single author of the Finalmask `fragment` and `noise` mask objects (M9-A spec §1.2), used
 * verbatim by [XrayConfigGenerator] and parsed back into a tree by [ObfuscationInjector], so the
 * two paths cannot drift.
 *
 * Every key is from `infra/conf/transport_finalmask.go` at `50231eaff98c`. See
 * `docs/agent/research/2026-10-11-m9a-fragment-noise-finalmask.md`. ARCHITECTURE.md §10.5 applies:
 * an invented key is silently ignored or rejects the whole config.
 */
internal object FinalmaskJson {
    fun fragmentMask(spec: FragmentSpec): String {
        val fields =
            buildList {
                add(""""packets":${jsonString(spec.packets.wire())}""")
                add(""""length":${jsonString(spec.length.wire())}""")
                // Finalmask's name; freedom's equivalent is `interval`. Happ's `fragmentation-interval`
                // maps here (M9-A spec §3.1).
                add(""""delay":${jsonString(spec.delay.wire())}""")
                spec.maxSplit?.let { add(""""maxSplit":${jsonString(it.wire())}""") }
            }
        return """{"type":"fragment","settings":{${fields.joinToString(",")}}}"""
    }

    fun noiseMask(spec: NoiseSpec): String {
        val items =
            spec.items.joinToString(",") { item ->
                val payload =
                    when (val p = item.payload) {
                        is NoisePayload.Random ->
                            """"rand":${jsonString(p.length.wire())},"randRange":${jsonString(p.byteRange.wire())}"""
                        is NoisePayload.Fixed ->
                            """"type":${jsonString(p.type.wire)},"packet":${packetJson(p)}"""
                    }
                """{$payload,"delay":${jsonString(item.delay.wire())}}"""
            }
        return """{"type":"noise","settings":{"noise":[$items]}}"""
    }

    /**
     * One mKCP `mkcp-legacy` mask (M9-D2 research §3.2, Xray-core v26.10.10 `infra/conf/transport_finalmask.go`
     * `MkcpLegacy {header, value}`): `{}` is the classic original obfuscation, `value` a seed, `header` a packet
     * header. Never both keys in one entry: with a header, `value` means a DNS domain, not a seed.
     */
    fun kcpMask(mask: KcpMask): String {
        val settings =
            when (mask) {
                KcpMask.Original -> ""
                is KcpMask.Seed -> """"value":${jsonString(mask.value)}"""
                is KcpMask.Header -> """"header":${jsonString(mask.header.wireName)}"""
            }
        return """{"type":"mkcp-legacy","settings":{$settings}}"""
    }

    /** The `finalmask` object's value, or null when there is nothing to emit. */
    fun block(obfuscation: Obfuscation): String? = streamBlock(obfuscation, emptyList())

    /**
     * The `finalmask` object's value for a stream that may carry mKCP masks, or null when there is nothing
     * to emit. `udp` is `[kcpMasks…, noise?]`: JSON index 0 is applied to the payload first, so the mKCP
     * masks (in [space.getsub.core.model.KcpRules.masks] order, seed or original before header) sit inside
     * and user noise goes last, nearest the wire (M9-D2 research §3.3 capture: noise anywhere else gets the
     * header wrapped around it). The caller has already dropped masks that do not apply to the outbound.
     */
    fun streamBlock(
        obfuscation: Obfuscation?,
        kcpMasks: List<KcpMask>,
    ): String? {
        val udp = kcpMasks.map(::kcpMask) + listOfNotNull(obfuscation?.noise?.let { noiseMask(it.spec) })
        val halves =
            buildList {
                obfuscation?.fragment?.let { add(""""tcp":[${fragmentMask(it.spec)}]""") }
                if (udp.isNotEmpty()) add(""""udp":[${udp.joinToString(",")}]""")
            }
        return if (halves.isEmpty()) null else "{${halves.joinToString(",")}}"
    }

    /**
     * A Hysteria2 outbound's `finalmask` object value, or null when there is nothing to emit
     * (M9-D1 spec §4, Xray-core v26.10.10 `infra/conf/transport_finalmask.go`).
     *
     * `udp` is `[salamander|gecko?, noise?, udphop?]`, in that order (plan ruling R6, research note
     * 2026-10-17 "Verified for D1" (c)). `finalmask.go:45-46` reverses the list, so JSON index 0 is
     * next to QUIC; `udphop` implements `HandleDial()` and must be the last element, or the dial fails
     * with `incorrect index: N *udphop.Config` (`finalmask.go:147-154`). [noise] is the user's global
     * M9-A noise; fragment is a TCP mask and never applies to this QUIC-only transport.
     *
     * `quicParams` only when congestion is set. Never `quicParams.udpHop` (gone in v26.10.10).
     */
    fun hysteria2Block(
        out: Hysteria2Outbound,
        noise: NoiseSpec?,
    ): String? {
        val udp =
            listOfNotNull(
                obfuscationMask(out.obfuscation),
                noise?.let(::noiseMask),
                out.hopping?.let { hop ->
                    // `remotePorts` is a PortList string; the first port stays `settings.port`, as
                    // libXray's share/hysteria2.go does.
                    val ports = hop.ports.joinToString(",") { r -> if (r.first == r.last) "${r.first}" else r.wire() }
                    """{"type":"udphop","settings":{"mode":"intervalLocal,intervalRemote",""" +
                        """"interval":${hop.intervalSeconds},"remotePorts":${jsonString(ports)}}}"""
                },
            )
        val halves =
            buildList {
                if (udp.isNotEmpty()) add(""""udp":[${udp.joinToString(",")}]""")
                quicParams(out.congestion)?.let { add(""""quicParams":$it""") }
            }
        return if (halves.isEmpty()) null else "{${halves.joinToString(",")}}"
    }

    // Salamander :728. `packetSize` is an Int32Range, which unmarshals from a number or "a-b" only.
    private fun obfuscationMask(obfuscation: Hysteria2Obfuscation): String? =
        when (obfuscation) {
            Hysteria2Obfuscation.None -> null
            is Hysteria2Obfuscation.Salamander ->
                """{"type":"salamander","settings":{"password":${jsonString(obfuscation.password)}}}"""
            is Hysteria2Obfuscation.Gecko ->
                """{"type":"salamander","settings":{"password":${jsonString(obfuscation.password)},""" +
                    """"packetSize":"${obfuscation.minPacket}-${obfuscation.maxPacket}"}}"""
        }

    // QuicParamsConfig :1132. A blank Bandwidth means unset to Bps(), so it is omitted rather than sent empty.
    private fun quicParams(congestion: Hysteria2Congestion): String? =
        when (congestion) {
            Hysteria2Congestion.Default -> null
            is Hysteria2Congestion.Bbr ->
                listOfNotNull(
                    """"congestion":"bbr"""",
                    // Lowercased as the core compares it; the generator refuses an unknown one first.
                    congestion.profile.lowercase().takeIf { it.isNotEmpty() }?.let {
                        """"bbrProfile":${jsonString(it)}"""
                    },
                ).joinToString(",", "{", "}")
            is Hysteria2Congestion.Brutal ->
                listOfNotNull(
                    """"congestion":"brutal"""",
                    congestion.up.takeIf { it.isNotBlank() }?.let { """"brutalUp":${jsonString(it)}""" },
                    congestion.down.takeIf { it.isNotBlank() }?.let { """"brutalDown":${jsonString(it)}""" },
                ).joinToString(",", "{", "}")
        }

    // `array` is a JSON byte array (PraseByteSlice unmarshals it into []byte); every other type is a string.
    private fun packetJson(p: NoisePayload.Fixed): String =
        if (p.type == NoisePacketType.Array) "[${p.packet}]" else jsonString(p.packet)
}

private fun IntRange.wire(): String = "$first-$last"

private fun FragmentPackets.wire(): String =
    when (this) {
        FragmentPackets.TlsHello -> "tlshello"
        is FragmentPackets.Records -> range.wire()
    }
