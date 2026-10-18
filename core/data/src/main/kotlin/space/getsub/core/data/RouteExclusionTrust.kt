// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import space.getsub.core.model.CidrRange
import space.getsub.core.model.ExcludedRoutes
import space.getsub.core.model.TuningChecked
import space.getsub.core.model.TuningProblem

internal const val DIRECTIVE_EXCLUDE_ROUTES = "exclude-routes"

/** Research R3: same format as [DIRECTIVE_EXCLUDE_ROUTES], and it replaces that list when present. */
internal const val DIRECTIVE_EXCLUDE_ROUTES_SET = "exclude-routes-set"

/**
 * M9-E spec §4: the per-subscription answer to the provider's `exclude-routes`, bound to the
 * [ExcludedRoutes.fingerprint] of the ranges the user was shown.
 */
public enum class RouteExclusionTrust {
    Unasked,
    Allowed,
    Denied,
    ;

    internal val stored: String get() = name.uppercase()
}

/** Null (never answered, or a row from before M9-E) and anything unknown read as [RouteExclusionTrust.Unasked]. */
internal fun routeExclusionTrustOf(stored: String?): RouteExclusionTrust =
    RouteExclusionTrust.entries.firstOrNull { it.stored == stored } ?: RouteExclusionTrust.Unasked

/**
 * What a subscription's provider currently asks to keep out of the tunnel, and the user's answer.
 *
 * [offered] is the provider's effective list as parsed (`exclude-routes-set` when present, else
 * `exclude-routes`), in the provider's order; empty when it sent none or sent a list that does not
 * parse. [applicable] is the part that can ever apply: [offered] less every range
 * [ExcludedRoutes.validate] calls too wide. Whether a range covers a DNS server is decided at
 * connect time, not here, so it is validated with no DNS addresses.
 *
 * [fingerprint] is [ExcludedRoutes.fingerprint] of [applicable] — the value an answer is bound to
 * and the one to pass to [SubscriptionRepository.setRouteExclusionTrust] — or null when
 * [applicable] is empty and there is nothing to ask. [trust] reads [RouteExclusionTrust.Unasked]
 * whenever the stored answer was given for a different fingerprint, so a changed list asks again
 * and a reordered one does not.
 *
 * ARCHITECTURE.md §5.6: excluded ranges are never logged, so [toString] carries counts only.
 */
public data class RouteExclusionStatus(
    val trust: RouteExclusionTrust,
    val fingerprint: String?,
    val offered: List<CidrRange>,
) {
    /** [offered] less the ranges that are refused whatever the user answers. */
    val applicable: List<CidrRange>
        get() = offered.filterIndexed { index, _ -> index !in tooWideIndices }

    /** The ranges in [offered] that are never applied: too wide (a whole family, or the TUN's own address). */
    val refused: List<CidrRange>
        get() = offered.filterIndexed { index, _ -> index in tooWideIndices }

    private val tooWideIndices: Set<Int>
        get() =
            ExcludedRoutes
                .validate(offered, dnsAddresses = emptyList())
                .filterIsInstance<TuningProblem.RangeTooWide>()
                .mapTo(mutableSetOf()) { it.index }

    override fun toString(): String =
        "RouteExclusionStatus(trust=$trust, fingerprint=${if (fingerprint == null) "none" else "<set>"}, " +
            "offered=${offered.size})"

    public companion object {
        /** No subscription, or nothing offered. */
        public val NONE: RouteExclusionStatus = RouteExclusionStatus(RouteExclusionTrust.Unasked, null, emptyList())
    }
}

/** Builds the status from the provider's effective directive value and the stored answer. */
internal fun routeExclusionStatusOf(
    value: String?,
    storedTrust: String?,
    storedFingerprint: String?,
): RouteExclusionStatus {
    val offered = (value?.let(ExcludedRoutes::parse) as? TuningChecked.Ok)?.value.orEmpty()
    val probe = RouteExclusionStatus(RouteExclusionTrust.Unasked, null, offered)
    val fingerprint = probe.applicable.takeIf { it.isNotEmpty() }?.let(ExcludedRoutes::fingerprint)
    val trust =
        if (fingerprint != null && fingerprint == storedFingerprint) {
            routeExclusionTrustOf(storedTrust)
        } else {
            RouteExclusionTrust.Unasked
        }
    return RouteExclusionStatus(trust, fingerprint, offered)
}
