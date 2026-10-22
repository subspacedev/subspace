// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles

import space.getsub.core.data.RouteExclusionStatus
import space.getsub.core.data.RouteExclusionTrust
import space.getsub.core.model.CidrRange

/**
 * The provider's request to keep [applied] out of the tunnel (M9-E spec §4), as the card shows it.
 *
 * The card names every range: [applied] is what Allow approves, [refused] is listed as never applied
 * whatever the answer. The answer is written against [fingerprint], the list the user was shown.
 *
 * ARCHITECTURE.md §5.6: excluded ranges are not logged, so [toString] carries counts only.
 */
internal data class RouteExclusionRequest(
    val fingerprint: String,
    val applied: List<String>,
    val refused: List<String>,
) {
    override fun toString(): String =
        "RouteExclusionRequest(fingerprint=<redacted>, applied=${applied.size}, refused=${refused.size})"
}

/**
 * The card's state: asked while unanswered for the current list and at least one range can apply.
 * An answered fingerprint never asks again; a list whose every range is refused asks nothing.
 */
internal fun RouteExclusionStatus.request(): RouteExclusionRequest? {
    val applicable = applicable
    val fingerprint = fingerprint?.takeIf { trust == RouteExclusionTrust.Unasked && applicable.isNotEmpty() }
    return fingerprint?.let {
        RouteExclusionRequest(
            fingerprint = it,
            applied = applicable.map(CidrRange::toString),
            refused = refused.map(CidrRange::toString),
        )
    }
}

/**
 * Writes the user's answer to [subscriptionId]'s card, then restarts a live session when the answer
 * can change what it runs, the way Settings → Tunnel's Save does: an Allow for the subscription that
 * delivered the active row. A Deny restarts nothing, since the card shows only while the list is
 * unanswered and nothing from it applied before. The service ignores the restart while disconnected.
 */
internal suspend fun ProfileSource.answerRouteExclusion(
    subscriptionId: Long,
    trust: RouteExclusionTrust,
    fingerprint: String,
) {
    setRouteExclusionTrust(subscriptionId, trust, fingerprint)
    if (trust == RouteExclusionTrust.Allowed && activeRowSubscriptionId() == subscriptionId) reapplyTuning()
}
