// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Rewrites a raw Hysteria outbound's pre-v26.10 port hopping into the shape the pinned core reads.
 *
 * Xray-core v26.7.11 read `finalmask.quicParams.udpHop {ports, interval}` (`infra/conf/transport_method.go`
 * `UdpHop`); v26.10.10 dropped it from `quicParams` and reads hopping only as a `finalmask.udp` mask
 * `{"type":"udphop","settings":{mode, interval, remotePorts}}` (`infra/conf/transport_finalmask.go:1053-1104`).
 * Remnawave 3.3.2 still writes the old shape, so without this a raw row would run without hopping
 * while its typed projection (which translates it) says it hops.
 *
 * `ports` becomes `remotePorts` and `interval` is copied when present (both are the same Xray types,
 * `PortList` and `Int32Range`; an absent interval is the core's default 30 in both shapes). The mask
 * goes last in `udp`: `udphop` dials the socket itself and the core refuses it anywhere else
 * (`transport/internet/finalmask/finalmask.go:147-154`). When the outbound already has a `udphop`
 * mask that one wins and only the legacy key is dropped. The legacy key always goes once handled,
 * and a `quicParams` left empty by that goes too.
 *
 * Only `hysteria` outbounds are touched, and only a shape this can read: `finalmask` an object,
 * `udp` absent or an array, `udpHop` an object whose `ports` is a JSON primitive. Anything else is
 * returned exactly as written. Pure; never throws.
 */
internal object LegacyUdpHop {
    private const val MODE = "intervalLocal,intervalRemote"

    fun apply(outbounds: JsonArray): JsonArray {
        var changed = false
        val rewritten =
            outbounds.map { element ->
                val outbound = element as? JsonObject
                val result = outbound?.let(::rewrite)
                if (result != null) changed = true
                result ?: element
            }
        return if (changed) JsonArray(rewritten) else outbounds
    }

    @Suppress("ReturnCount")
    private fun rewrite(outbound: JsonObject): JsonObject? {
        val stream = outbound["streamSettings"] as? JsonObject ?: return null
        if (!outbound.isHysteria(stream)) return null
        val finalmask = stream["finalmask"] as? JsonObject ?: return null
        val newFinalmask = rewriteFinalmask(finalmask) ?: return null
        val newStream = stream.toMutableMap().apply { put("finalmask", newFinalmask) }
        return JsonObject(outbound.toMutableMap().apply { put("streamSettings", JsonObject(newStream)) })
    }

    private fun JsonObject.isHysteria(stream: JsonObject): Boolean {
        val network = (stream["network"] as? JsonPrimitive)?.content?.lowercase()
        val protocol = (this["protocol"] as? JsonPrimitive)?.content?.lowercase()
        return protocol == "hysteria" || network == "hysteria"
    }

    /** The rewritten finalmask, or null when there is no legacy shape this can read. */
    @Suppress("ReturnCount")
    private fun rewriteFinalmask(finalmask: JsonObject): JsonObject? {
        val quic = finalmask["quicParams"] as? JsonObject ?: return null
        val hop = quic["udpHop"] as? JsonObject ?: return null
        val ports = (hop["ports"] as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: return null
        val udpElement = finalmask["udp"]?.takeUnless { it is JsonNull } ?: JsonArray(emptyList())
        val udp = udpElement as? JsonArray ?: return null

        val hasMask = udp.any { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "udphop" }
        val newUdp = if (hasMask) udp else JsonArray(udp + udphopMask(ports, hop["interval"]))
        val newQuic = quic - "udpHop"
        return JsonObject(
            finalmask.toMutableMap().apply {
                put("udp", newUdp)
                if (newQuic.isEmpty()) remove("quicParams") else put("quicParams", JsonObject(newQuic))
            },
        )
    }

    private fun udphopMask(
        ports: JsonPrimitive,
        interval: JsonElement?,
    ): JsonObject {
        val settings =
            buildMap {
                put("mode", JsonPrimitive(MODE))
                if (interval != null && interval !is JsonNull) put("interval", interval)
                put("remotePorts", ports)
            }
        return JsonObject(mapOf("type" to JsonPrimitive("udphop"), "settings" to JsonObject(settings)))
    }
}
