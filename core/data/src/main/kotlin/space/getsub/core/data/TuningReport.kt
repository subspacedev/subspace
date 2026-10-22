// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.getsub.core.model.MuxApplicability
import space.getsub.core.model.MuxSpec
import space.getsub.core.model.TuningOrigin
import space.getsub.core.parser.EmittedSniffing
import space.getsub.core.parser.MuxDetection
import space.getsub.core.parser.MuxFate
import space.getsub.core.parser.MuxOffer
import space.getsub.core.parser.MuxPlan
import space.getsub.core.parser.SniffingDecision
import space.getsub.core.parser.emittedSniffing
import space.getsub.core.parser.ownSocksSniffing

/**
 * What server details say about a row's sniffing and mux (M9-E spec §1, §3).
 *
 * @property muxUsed whether the tunnel actually runs mux for this row: [mux] is on and the row's
 *   outermost proxy outbound is one the core can mux. Mux on with this false is "Mux isn't used for
 *   this server".
 */
public data class TuningReport(
    val sniffing: TuningValue<Boolean>,
    val mux: TuningValue<MuxSpec?>,
    val muxUsed: Boolean,
)

private val LENIENT = Json { isLenient = true }

/**
 * The report for [profile] given its [resolved] tuning; pure so it is testable without a database.
 *
 * A row that does not run as written (typed, or raw running its typed projection) runs exactly the
 * resolution, with mux used where [MuxApplicability] allows it. A row that runs as written is read
 * from its stored bytes with the same decisions the composer executes: [MuxDetection.plan] for mux,
 * [emittedSniffing] for sniffing. Where the config's own block is kept, the origin is
 * [TuningOrigin.Config] and the value is the config's own.
 *
 * Sniffing is modelled on the pure passthrough branch, as the editor's advisory is: with routing or
 * a DNS plan active the app's block replaces the config's own whatever this says.
 */
internal fun tuningReport(
    profile: StoredProfile?,
    resolved: ResolvedTuning,
): TuningReport {
    val rawJson = profile?.rawJson?.takeIf { profile.runsAsWritten }
    if (rawJson == null) {
        val outbound = profile?.outbound
        val used = resolved.mux.value != null && outbound != null && MuxApplicability.applies(outbound)
        return TuningReport(resolved.sniffing, resolved.mux, used)
    }
    val root = parse(rawJson)
    val outbounds = (root?.get("outbounds") as? JsonArray).orEmpty()
    val (mux, muxUsed) = rawMux(resolved.mux, MuxDetection.plan(outbounds, resolved.mux.offer()))
    return TuningReport(rawSniffing(root, resolved.sniffing), mux, muxUsed)
}

private fun TuningValue<MuxSpec?>.offer(): MuxOffer? =
    when {
        origin == TuningOrigin.Yours -> MuxOffer(on = value != null, replaceOwn = true)
        value != null -> MuxOffer(on = true, replaceOwn = false)
        else -> null
    }

/**
 * Mirrors the obfuscation report's raw rule: Yours if your mux is written anywhere; otherwise Config
 * if any outermost outbound keeps its own block; otherwise the resolution, used only where it is
 * written.
 */
private fun rawMux(
    resolved: TuningValue<MuxSpec?>,
    plans: List<MuxPlan>,
): Pair<TuningValue<MuxSpec?>, Boolean> {
    val ours = plans.any { it.fate == MuxFate.Ours }
    val own = plans.firstOrNull { it.fate == MuxFate.Own }
    return when {
        ours && resolved.origin == TuningOrigin.Yours -> resolved to true
        own != null -> {
            // The own block runs only where the core can mux that outbound, whatever its siblings are.
            val used = own.ownSpec != null && own.eligible
            TuningValue(own.ownSpec, TuningOrigin.Config) to used
        }
        else -> resolved to ours
    }
}

private fun rawSniffing(
    root: JsonObject?,
    resolved: TuningValue<Boolean>,
): TuningValue<Boolean> {
    val decision = SniffingDecision(resolved.value, replacesOwn = resolved.origin == TuningOrigin.Yours)
    val emitted = emittedSniffing(root?.let(::ownSocksSniffing), decision, fakeDns = false)
    return if (emitted is EmittedSniffing.Own) {
        TuningValue((emitted.block["enabled"] as? JsonPrimitive)?.content == "true", TuningOrigin.Config)
    } else {
        resolved
    }
}

private fun parse(rawJson: String): JsonObject? =
    try {
        LENIENT.parseToJsonElement(rawJson) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
