// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.getsub.core.model.Applied
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.NoiseSpec
import space.getsub.core.model.Obfuscation
import space.getsub.core.parser.MaskFate
import space.getsub.core.parser.MaskOffer
import space.getsub.core.parser.ObfuscationDetection
import space.getsub.core.parser.OutboundPlan

/**
 * Applies M9-A spec §3.3 to a raw config's own `outbounds`. Every other mask, key and outbound is
 * left exactly as written. That is the narrowed "as written" promise of M9-A spec §1.4.
 */
internal object ObfuscationInjector {
    fun apply(
        outbounds: JsonArray,
        obfuscation: Obfuscation?,
    ): JsonArray {
        if (obfuscation == null || (obfuscation.fragment == null && obfuscation.noise == null)) return outbounds
        // The decision is ObfuscationDetection's, shared with the server report; this only executes it.
        val plans =
            ObfuscationDetection
                .plan(outbounds, obfuscation.fragment?.offer(), obfuscation.noise?.offer())
                .associateBy { it.index }
        return JsonArray(
            outbounds.mapIndexed { i, element ->
                val outbound = element as? JsonObject
                val plan = plans[i]
                if (plan == null || outbound == null || !plan.touched) element else rewrite(outbound, plan, obfuscation)
            },
        )
    }

    private fun Applied<*>.offer(): MaskOffer = MaskOffer(replaceOwn)

    /** [plan] is touched, so the outbound is editable (rule 7); the fallbacks only narrow types. */
    @Suppress("ReturnCount")
    private fun rewrite(
        outbound: JsonObject,
        plan: OutboundPlan,
        obfuscation: Obfuscation,
    ): JsonObject {
        val stream = outbound.absentOr("streamSettings") { JsonObject(emptyMap()) } as? JsonObject ?: return outbound
        val finalmask = stream.absentOr("finalmask") { JsonObject(emptyMap()) } as? JsonObject ?: return outbound
        val tcp = finalmask.absentOr("tcp") { JsonArray(emptyList()) } as? JsonArray ?: return outbound
        val udp = finalmask.absentOr("udp") { JsonArray(emptyList()) } as? JsonArray ?: return outbound

        val fragment = obfuscation.fragment?.takeIf { plan.fragment == MaskFate.Ours }
        val noise = obfuscation.noise?.takeIf { plan.noise == MaskFate.Ours }
        val newTcp = if (fragment != null) withFragment(tcp, fragment.spec) else tcp
        val newUdp = if (noise != null) withNoise(udp, noise.spec) else udp
        return assemble(outbound, stream, finalmask.toMutableMap(), newTcp to newUdp, plan.cutLink)
    }

    // JSON null reads as absent, as Xray reads it.
    private fun JsonObject.absentOr(
        key: String,
        default: () -> JsonElement,
    ): JsonElement = this[key]?.takeUnless { it is JsonNull } ?: default()

    private fun assemble(
        outbound: JsonObject,
        stream: JsonObject,
        finalmask: MutableMap<String, JsonElement>,
        masks: Pair<JsonArray, JsonArray>,
        cutLink: Boolean,
    ): JsonObject {
        finalmask.putOrRemove("tcp", masks.first)
        finalmask.putOrRemove("udp", masks.second)
        val newStream = stream.toMutableMap().apply { put("finalmask", JsonObject(finalmask)) }
        if (cutLink) {
            // Ruling R4: only the link goes. The freedom outbound stays, since other outbounds or rules may name it.
            val sockopt = newStream["sockopt"] as? JsonObject
            if (sockopt != null) newStream["sockopt"] = JsonObject(sockopt - "dialerProxy")
        }
        return JsonObject(outbound.toMutableMap().apply { put("streamSettings", JsonObject(newStream)) })
    }

    // Index 0, outermost: the only place `tlshello` sees a real ClientHello (research note, "Mask order").
    private fun withFragment(
        tcp: JsonArray,
        spec: FragmentSpec,
    ): JsonArray = JsonArray(listOf(parse(FinalmaskJson.fragmentMask(spec))) + tcp.withoutType("fragment"))

    // Last, nearest the wire, except before a mask that dials the socket itself. Xray-core v26.10.10
    // `transport/internet/finalmask/finalmask.go:147-154`: a `HandleDial()` mask (`udphop`, `xicmp`)
    // anywhere but last in JSON fails every dial with `incorrect index: N *udphop.Config`, so noise
    // appended after a raw Hysteria2 outbound's own `udphop` would be a config the core refuses.
    private fun withNoise(
        udp: JsonArray,
        spec: NoiseSpec,
    ): JsonArray {
        val others = udp.withoutType("noise")
        val dialing = others.indexOfFirst { it.maskType() in DIALING_MASKS }.takeIf { it >= 0 } ?: others.size
        val ours = parse(FinalmaskJson.noiseMask(spec))
        return JsonArray(others.subList(0, dialing) + ours + others.subList(dialing, others.size))
    }

    private fun MutableMap<String, JsonElement>.putOrRemove(
        key: String,
        array: JsonArray,
    ) {
        if (array.isNotEmpty()) put(key, array) else remove(key)
    }

    // A directive-mode pass removes nothing: it only runs when no own mask is present.
    private fun JsonArray.withoutType(type: String): List<JsonElement> =
        filterNot { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.content == type }

    private fun parse(json: String): JsonElement = Json.parseToJsonElement(json)
}

/** Masks that dial the socket themselves (`HandleDial()`); the core requires them last in `udp`. */
private val DIALING_MASKS = setOf("udphop", "xicmp")

private fun JsonElement.maskType(): String? = ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.content
