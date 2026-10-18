// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import space.getsub.core.model.Applied
import space.getsub.core.model.MuxSpec
import space.getsub.core.parser.MuxDetection
import space.getsub.core.parser.MuxFate
import space.getsub.core.parser.MuxOffer

/**
 * Applies M9-E spec §3 to a raw config's own `outbounds`: the outermost proxy outbounds, the same
 * ones [ObfuscationInjector] masks. Every other key and outbound is left exactly as written; an
 * outbound the core cannot mux is never touched, even when it carries a `mux` of its own.
 */
internal object MuxInjector {
    fun apply(
        outbounds: JsonArray,
        mux: Applied<MuxSpec?>?,
    ): JsonArray {
        if (mux == null) return outbounds
        val spec = mux.spec
        // The decision is MuxDetection's, shared with the tuning report; this only executes it.
        val fates =
            MuxDetection
                .plan(outbounds, MuxOffer(on = spec != null, replaceOwn = mux.replaceOwn))
                .associate { it.index to it.fate }
        return JsonArray(
            outbounds.mapIndexed { i, element ->
                val outbound = element as? JsonObject
                when {
                    outbound == null -> element
                    fates[i] == MuxFate.Ours && spec != null -> JsonObject(outbound + ("mux" to muxBlock(spec)))
                    fates[i] == MuxFate.Removed -> JsonObject(outbound - "mux")
                    else -> element
                }
            },
        )
    }

    // Xray-core v26.10.10 `infra/conf/xray.go:106-128`, the same four keys the generator writes.
    private fun muxBlock(spec: MuxSpec): JsonObject =
        buildJsonObject {
            put("enabled", true)
            put("concurrency", spec.concurrency)
            put("xudpConcurrency", spec.xudpConcurrency)
            put("xudpProxyUDP443", spec.udp443.wire)
        }
}
