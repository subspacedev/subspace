// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// The file is named for the rule; PickCandidate is only its input type.
@file:Suppress("MatchingDeclarationName")

package space.getsub.core.model.automation

import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult

/**
 * A server the fastest-pick may choose. [listIndex] is its place in the on-screen list, the
 * last tiebreak.
 *
 * ARCHITECTURE.md §5.6: [name] is provider-chosen, so [toString] leaves it out.
 */
public data class PickCandidate(
    val id: Long,
    val name: String,
    val listIndex: Int,
) {
    override fun toString(): String = "PickCandidate(id=$id, name=<redacted>, listIndex=$listIndex)"
}

/**
 * The id of the fastest server, or null when none qualifies (M10-C spec §3.2).
 *
 * Only OK results count. With [useMarkers], servers whose name marks them for another network
 * are skipped. A tie goes to [activeId] (no needless reconnect), then to the lower list index.
 */
public fun pickFastest(
    candidates: List<PickCandidate>,
    results: Map<Long, LatencyResult>,
    network: NetworkKind,
    useMarkers: Boolean,
    activeId: Long?,
): Long? =
    candidates
        .filter { !useMarkers || networkMarkerOf(it.name).allowedOn(network) }
        .mapNotNull { c -> results[c.id]?.takeIf { it.outcome == LatencyOutcome.OK }?.let { c to it.delayMillis } }
        .minWithOrNull(
            compareBy<Pair<PickCandidate, Int>> { it.second }
                .thenBy { if (it.first.id == activeId) 0 else 1 }
                .thenBy { it.first.listIndex },
        )?.first
        ?.id
