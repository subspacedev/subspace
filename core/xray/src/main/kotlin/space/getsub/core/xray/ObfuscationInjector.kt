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
import space.getsub.core.parser.ObfuscationDetection
import space.getsub.core.parser.OwnMask

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
        val byTag = ObfuscationDetection.tagIndex(outbounds)
        val targets = ObfuscationDetection.outermostEligible(outbounds)
        return JsonArray(
            outbounds.mapIndexed { i, element ->
                val outbound = element as? JsonObject
                if (i !in targets || outbound == null) element else rewrite(outbound, byTag, obfuscation) ?: element
            },
        )
    }

    /** Null when the outbound's shape is not one this can safely edit (rule 7). */
    @Suppress("ReturnCount")
    private fun rewrite(
        outbound: JsonObject,
        byTag: Map<String, JsonObject>,
        obfuscation: Obfuscation,
    ): JsonObject? {
        val stream = outbound.absentOr("streamSettings") { JsonObject(emptyMap()) } as? JsonObject ?: return null
        val finalmask = stream.absentOr("finalmask") { JsonObject(emptyMap()) } as? JsonObject ?: return null
        val tcp = finalmask.absentOr("tcp") { JsonArray(emptyList()) } as? JsonArray ?: return null
        val udp = finalmask.absentOr("udp") { JsonArray(emptyList()) } as? JsonArray ?: return null

        val fragmentOwn = ObfuscationDetection.ownFragment(outbound, byTag)
        val noiseOwn = ObfuscationDetection.ownNoise(outbound, byTag)
        // Cut first, then re-evaluate: a directive fills the gap a cut leaves (ruling, fix round 1).
        val cut = cutsLink(obfuscation.fragment, fragmentOwn) || cutsLink(obfuscation.noise, noiseOwn)
        val fragment = obfuscation.fragment?.let { Step(fragmentOwn, it, cut) }
        val noise = obfuscation.noise?.let { Step(noiseOwn, it, cut) }
        if (fragment?.applies != true && noise?.applies != true && !cut) return outbound

        val newTcp = if (fragment?.applies == true) withFragment(tcp, fragment.applied.spec) else tcp
        val newUdp = if (noise?.applies == true) withNoise(udp, noise.applied.spec) else udp
        return assemble(outbound, stream, finalmask.toMutableMap(), newTcp to newUdp, cut)
    }

    private fun cutsLink(
        applied: Applied<*>?,
        own: OwnMask,
    ): Boolean = applied?.replaceOwn == true && own.freedomTag != null

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

    // Last, nearest the wire.
    private fun withNoise(
        udp: JsonArray,
        spec: NoiseSpec,
    ): JsonArray = JsonArray(udp.withoutType("noise") + parse(FinalmaskJson.noiseMask(spec)))

    /** One directive against one outbound's own mask. */
    private class Step<T>(val own: OwnMask, val applied: Applied<T>, val cut: Boolean) {
        private val effectivePresent: Boolean get() = own.inFinalmask || (own.freedomTag != null && !cut)
        val applies: Boolean get() = applied.replaceOwn || !effectivePresent
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
