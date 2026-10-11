// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Whether one outbound already does a mask itself, and how (M9-A spec §3.3 rule 3). */
public data class OwnMask(
    val inFinalmask: Boolean,
    val freedomTag: String?,
) {
    val present: Boolean get() = inFinalmask || freedomTag != null
}

/** Whether any outermost eligible outbound of a config fragments / adds noise itself. */
public data class OwnMasks(
    val fragment: Boolean,
    val noise: Boolean,
)

/**
 * Reads a raw config's outbounds for M9-A spec §3.3. Pure and tolerant: a shape it does not
 * recognise is "not an own mask" and "not eligible", never an exception. Shared by
 * `:core:xray`'s injector and the UI's "from config" line, so they cannot disagree.
 */
public object ObfuscationDetection {
    /** M9-A spec §3.3 rule 1. Anything else is left alone (ARCHITECTURE.md §10.5). */
    public val ELIGIBLE_PROTOCOLS: Set<String> =
        setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "socks", "http")

    private val LENIENT = Json { isLenient = true }

    /** Indices of eligible outbounds that are the hop which reaches the network (rule 2). */
    public fun outermostEligible(outbounds: List<JsonElement>): Set<Int> {
        val objects = outbounds.map { it as? JsonObject }
        val eligibleTags =
            objects
                .filterNotNull()
                .filter { it.protocol() in ELIGIBLE_PROTOCOLS }
                .mapNotNull { it.string("tag") }
                .toSet()
        return objects.indices
            .filter { i ->
                val outbound = objects[i] ?: return@filter false
                outbound.protocol() in ELIGIBLE_PROTOCOLS && outbound.dialerProxy() !in eligibleTags
            }.toSet()
    }

    public fun ownFragment(
        outbound: JsonObject,
        byTag: Map<String, JsonObject>,
    ): OwnMask = own(outbound, byTag, half = "tcp", maskType = "fragment", freedomKey = "fragment")

    public fun ownNoise(
        outbound: JsonObject,
        byTag: Map<String, JsonObject>,
    ): OwnMask = own(outbound, byTag, half = "udp", maskType = "noise", freedomKey = "noises")

    /** For the UI: does this config fragment / add noise by itself anywhere it would matter? */
    public fun configOwn(rawJson: String): OwnMasks {
        val outbounds =
            try {
                (LENIENT.parseToJsonElement(rawJson) as? JsonObject)?.get("outbounds") as? JsonArray
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return OwnMasks(fragment = false, noise = false)
        val byTag = tagIndex(outbounds)
        val outer = outermostEligible(outbounds).map { outbounds[it] as JsonObject }
        return OwnMasks(
            fragment = outer.any { ownFragment(it, byTag).present },
            noise = outer.any { ownNoise(it, byTag).present },
        )
    }

    public fun tagIndex(outbounds: List<JsonElement>): Map<String, JsonObject> =
        outbounds.mapNotNull { it as? JsonObject }.mapNotNull { o -> o.string("tag")?.let { it to o } }.toMap()

    private fun own(
        outbound: JsonObject,
        byTag: Map<String, JsonObject>,
        half: String,
        maskType: String,
        freedomKey: String,
    ): OwnMask {
        val masks = ((outbound.stream()?.get("finalmask") as? JsonObject)?.get(half) as? JsonArray).orEmpty()
        val inFinalmask = masks.any { (it as? JsonObject)?.string("type") == maskType }
        val chained = outbound.dialerProxy()?.let { byTag[it] }
        val chainedSettings = chained?.get("settings") as? JsonObject
        val freedomTag =
            chained
                ?.takeIf { it.protocol() == "freedom" && chainedSettings?.containsKey(freedomKey) == true }
                ?.string("tag")
        return OwnMask(inFinalmask, freedomTag)
    }

    private fun JsonObject.stream(): JsonObject? = this["streamSettings"] as? JsonObject

    private fun JsonObject.dialerProxy(): String? = (stream()?.get("sockopt") as? JsonObject)?.string("dialerProxy")

    private fun JsonObject.protocol(): String? = string("protocol")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
