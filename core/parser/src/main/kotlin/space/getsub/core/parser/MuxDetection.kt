// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import space.getsub.core.model.MuxSpec
import space.getsub.core.model.Udp443Mode

/**
 * The mux the app has for a raw config (M9-E spec §3). [on] false is your switch On with mux off.
 * [replaceOwn] is true exactly for your own setting: it displaces the config's own `mux` block; a
 * provider directive never does. A provider's mux-off, or the default, is no offer at all.
 */
public data class MuxOffer(
    val on: Boolean,
    val replaceOwn: Boolean,
)

/** What one outermost proxy outbound's `mux` ends up as. */
public enum class MuxFate {
    /** The app's `mux` block is written. */
    Ours,

    /** The config's own `mux` block runs as written. */
    Own,

    /** The config's own `mux` block is removed: your switch is On with mux off. */
    Removed,

    /** No `mux` block, before or after. */
    Nothing,
}

/**
 * The verdict for the outermost proxy outbound at [index] in `outbounds`.
 *
 * @property eligible whether mux.cool can serve this outbound ([carriesMux], the raw twin of
 *   `MuxApplicability.applies`). An ineligible outbound is never touched.
 * @property ownSpec the config's own `mux` block read as a spec, or null when it has none or it is
 *   not `enabled: true`.
 */
public data class MuxPlan(
    val index: Int,
    val eligible: Boolean,
    val fate: MuxFate,
    val ownSpec: MuxSpec?,
)

/**
 * Reads a raw config's outbounds for M9-E spec §3. Pure and tolerant, like [ObfuscationDetection],
 * whose outermost-outbound selection it shares: the same outbounds masks go on get mux.
 *
 * [plan] is the single decision: `:core:xray`'s `MuxInjector` executes it and `:core:data`'s
 * tuning report describes it, so the two cannot disagree about what a raw config runs.
 */
public object MuxDetection {
    private val LENIENT = Json { isLenient = true }

    /** Every outermost proxy outbound, in index order; nothing else is ever touched. */
    public fun plan(
        outbounds: List<JsonElement>,
        offer: MuxOffer?,
    ): List<MuxPlan> =
        ObfuscationDetection.outermostEligible(outbounds).sorted().map { i ->
            val outbound = outbounds[i] as JsonObject
            val hasOwn = outbound.present("mux") != null
            val eligible = outbound.carriesMux()
            val fate =
                when {
                    !eligible || offer == null || (hasOwn && !offer.replaceOwn) ->
                        if (hasOwn) MuxFate.Own else MuxFate.Nothing
                    offer.on -> MuxFate.Ours
                    hasOwn -> MuxFate.Removed
                    else -> MuxFate.Nothing
                }
            MuxPlan(i, eligible, fate, ownSpec(outbound))
        }

    /** [plan] over a raw config's text. Junk, or no `outbounds` array, plans nothing. */
    public fun plan(
        rawJson: String,
        offer: MuxOffer?,
    ): List<MuxPlan> {
        val outbounds =
            try {
                (LENIENT.parseToJsonElement(rawJson) as? JsonObject)?.get("outbounds") as? JsonArray
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return emptyList()
        return plan(outbounds, offer)
    }

    // Absent numbers read as 0 and an absent mode as reject, as Xray-core v26.10.10's MuxConfig does
    // (`infra/conf/xray.go:106-128`). An unknown mode also reads as reject here, which the core does
    // not do: it refuses to build the config, so this spec describes a config that never starts.
    private fun ownSpec(outbound: JsonObject): MuxSpec? {
        val mux =
            (outbound.present("mux") as? JsonObject)
                ?.takeIf { (it["enabled"] as? JsonPrimitive)?.content == "true" }
                ?: return null
        val mode = mux.string("xudpProxyUDP443")
        return MuxSpec(
            concurrency = mux.int("concurrency"),
            xudpConcurrency = mux.int("xudpConcurrency"),
            udp443 = Udp443Mode.entries.firstOrNull { it.wire == mode } ?: Udp443Mode.Reject,
        )
    }

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
}
