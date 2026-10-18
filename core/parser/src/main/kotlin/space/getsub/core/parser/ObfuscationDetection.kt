// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.getsub.core.model.ObfuscationApplicability

/** Whether one outbound already does a mask itself, and how (M9-A spec §3.3 rule 3). */
public data class OwnMask(
    val inFinalmask: Boolean,
    val freedomTag: String?,
) {
    val present: Boolean get() = inFinalmask || freedomTag != null
}

/**
 * A mask the app has on offer for a raw config. [replaceOwn] is true exactly for the user's own
 * values (M9-A plan ruling R1): they displace the config's own mask; a directive never does.
 */
public data class MaskOffer(
    val replaceOwn: Boolean,
)

/** What one mask ends up as on one outbound once M9-A spec §3.3 has run. */
public enum class MaskFate {
    /** The app's mask is injected. */
    Ours,

    /** The config's own mask (Finalmask entry or freedom chain) still runs. */
    Own,

    /** Neither. */
    Nothing,
}

/**
 * M9-A spec §3.3's verdict for one outermost eligible outbound, at [index] in `outbounds`.
 *
 * @property editable false for rule 7's malformed shape: the outbound runs exactly as written.
 * @property cutLink the `sockopt.dialerProxy` link to a fragmenting or noising `freedom` outbound
 *   is removed. The freedom outbound itself always stays.
 */
public data class OutboundPlan(
    val index: Int,
    val editable: Boolean,
    val cutLink: Boolean,
    val fragment: MaskFate,
    val noise: MaskFate,
) {
    /** Whether the injector changes this outbound at all. */
    val touched: Boolean get() = cutLink || fragment == MaskFate.Ours || noise == MaskFate.Ours
}

/**
 * Reads a raw config's outbounds for M9-A spec §3.3. Pure and tolerant: a shape it does not
 * recognise is "not an own mask" and "not eligible", never an exception.
 *
 * [plan] is the single decision: `:core:xray`'s injector executes it and `:core:data`'s server
 * report describes it, so the two cannot disagree about what a raw config ends up running.
 */
public object ObfuscationDetection {
    /**
     * M9-A spec §3.3 rule 1. Anything else is left alone (ARCHITECTURE.md §10.5). `wireguard` since M9-D2:
     * it dials its own UDP socket and honours `streamSettings.finalmask` UDP masks (research §1.3).
     */
    public val ELIGIBLE_PROTOCOLS: Set<String> =
        setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "socks", "http", "wireguard")

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
                val outbound = objects[i]
                outbound != null &&
                    outbound.protocol() in ELIGIBLE_PROTOCOLS &&
                    outbound.dialerProxy() !in eligibleTags
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

    /**
     * M9-A spec §3.3 rules 2 to 7 for every outermost eligible outbound, in index order. An
     * outbound absent from the result is never touched. A null offer means no mask of that type.
     *
     * Cut first, then re-evaluate (ruling, fix round 1): when the user's mask of one type cuts a
     * freedom-chain link, the config's chained mask of the *other* type goes with it, so a
     * directive of that other type fills the gap, and without one nothing does.
     */
    public fun plan(
        outbounds: List<JsonElement>,
        fragment: MaskOffer?,
        noise: MaskOffer?,
    ): List<OutboundPlan> {
        val byTag = tagIndex(outbounds)
        return outermostEligible(outbounds).sorted().map { i ->
            val outbound = outbounds[i] as JsonObject
            // Fragment is a TCP mask; a QUIC-only Hysteria outbound never dials TCP (M9-D1 spec §4),
            // nor do WireGuard and mKCP (M9-D2 research §5.3), so it is not offered there rather than
            // written in and reported as applied. Noise is a UDP mask, likewise never offered to the
            // CONNECT-only HTTP outbound or an HTTPUpgrade stream. The typed path asks the same table,
            // `ObfuscationApplicability`.
            val dials = outbound.dials()
            plan(
                i,
                outbound,
                byTag,
                fragment.takeUnless { dials == Dials.UdpOnly },
                noise.takeUnless { dials == Dials.TcpOnly },
            )
        }
    }

    /** [plan] over a raw config's text. Junk, or no `outbounds` array, plans nothing. */
    public fun plan(
        rawJson: String,
        fragment: MaskOffer?,
        noise: MaskOffer?,
    ): List<OutboundPlan> {
        val outbounds =
            try {
                (LENIENT.parseToJsonElement(rawJson) as? JsonObject)?.get("outbounds") as? JsonArray
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return emptyList()
        return plan(outbounds, fragment, noise)
    }

    private fun plan(
        index: Int,
        outbound: JsonObject,
        byTag: Map<String, JsonObject>,
        fragment: MaskOffer?,
        noise: MaskOffer?,
    ): OutboundPlan {
        val fragmentOwn = ownFragment(outbound, byTag)
        val noiseOwn = ownNoise(outbound, byTag)
        if (!editable(outbound)) {
            return OutboundPlan(
                index,
                editable = false,
                cutLink = false,
                fragment = fragmentOwn.untouched(),
                noise = noiseOwn.untouched(),
            )
        }
        val cut = cutsLink(fragment, fragmentOwn) || cutsLink(noise, noiseOwn)
        return OutboundPlan(
            index,
            editable = true,
            cutLink = cut,
            fragment = fate(fragment, fragmentOwn, cut),
            noise = fate(noise, noiseOwn, cut),
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
}

private fun OwnMask.untouched(): MaskFate = if (present) MaskFate.Own else MaskFate.Nothing

private fun cutsLink(
    offer: MaskOffer?,
    own: OwnMask,
): Boolean = offer?.replaceOwn == true && own.freedomTag != null

private fun fate(
    offer: MaskOffer?,
    own: OwnMask,
    cut: Boolean,
): MaskFate {
    val ownSurvivesCut = own.inFinalmask || (own.freedomTag != null && !cut)
    return when {
        offer != null && (offer.replaceOwn || !ownSurvivesCut) -> MaskFate.Ours
        ownSurvivesCut -> MaskFate.Own
        else -> MaskFate.Nothing
    }
}

/** Rule 7: a shape this cannot safely edit. JSON null reads as absent, as Xray reads it. */
private fun editable(outbound: JsonObject): Boolean {
    val stream = outbound.present("streamSettings")
    val finalmask = (stream as? JsonObject)?.present("finalmask")
    val halves = (finalmask as? JsonObject)?.let { f -> listOf(f.present("tcp"), f.present("udp")) }.orEmpty()
    return (stream == null || stream is JsonObject) &&
        (finalmask == null || finalmask is JsonObject) &&
        halves.all { it == null || it is JsonArray }
}

private fun JsonObject.present(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

private fun JsonObject.stream(): JsonObject? = this["streamSettings"] as? JsonObject

private fun JsonObject.dialerProxy(): String? = (stream()?.get("sockopt") as? JsonObject)?.string("dialerProxy")

private fun JsonObject.protocol(): String? = string("protocol")

/** Which socket kinds an outbound dials, for mask applicability (mirrors `ObfuscationApplicability`). */
private enum class Dials { TcpOnly, UdpOnly, Both }

private fun JsonObject.dials(): Dials {
    val network = stream()?.string("network").orEmpty()
    val protocol = protocol()
    return when {
        protocol == "hysteria" || network.equals("hysteria", ignoreCase = true) -> Dials.UdpOnly
        protocol == "wireguard" || ObfuscationApplicability.isKcpNetwork(network) -> Dials.UdpOnly
        protocol == "http" || ObfuscationApplicability.isHttpUpgradeNetwork(network) -> Dials.TcpOnly
        else -> Dials.Both
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
