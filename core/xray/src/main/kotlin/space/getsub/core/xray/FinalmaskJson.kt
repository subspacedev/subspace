// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSpec
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

    /** The `finalmask` object's value, or null when there is nothing to emit. */
    fun block(obfuscation: Obfuscation): String? {
        val halves =
            buildList {
                obfuscation.fragment?.let { add(""""tcp":[${fragmentMask(it.spec)}]""") }
                obfuscation.noise?.let { add(""""udp":[${noiseMask(it.spec)}]""") }
            }
        return if (halves.isEmpty()) null else "{${halves.joinToString(",")}}"
    }

    private fun FragmentPackets.wire(): String =
        when (this) {
            FragmentPackets.TlsHello -> "tlshello"
            is FragmentPackets.Records -> range.wire()
        }

    // `array` is a JSON byte array (PraseByteSlice unmarshals it into []byte); every other type is a string.
    private fun packetJson(p: NoisePayload.Fixed): String =
        if (p.type == NoisePacketType.Array) "[${p.packet}]" else jsonString(p.packet)
}
