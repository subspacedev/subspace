// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.core.data.ResolvedTuning
import space.getsub.core.model.CidrRange
import space.getsub.core.model.DnsValidation
import space.getsub.core.model.ExcludedRoutes
import space.getsub.core.xray.DnsPlan

/**
 * The planned shape of a TUN interface: what `VpnService.Builder` is given and what nothing can change
 * without rebuilding the interface (M9-E spec §4, §7.1; ARCHITECTURE.md §5.2).
 *
 * Planned, not accepted, in the sense [advertisedTunDnsAddress] argues for [advertisedDns]: a builder
 * fallback is a function of the planned value, so two equal shapes always build equal interfaces.
 * [excluded] is the session's list after [space.getsub.core.data.dropRangesCoveringDns], in resolver
 * order; a reordered list compares unequal and costs one rebuild, never a kept stale interface.
 */
internal data class TunShape(
    val mtu: Int,
    val advertisedDns: String,
    val excluded: List<CidrRange>,
) {
    // ARCHITECTURE.md §5.6: the resolver and the user's LAN layout are private; the count is not.
    override fun toString(): String = "TunShape(mtu=$mtu, advertisedDns=<redacted>, excluded=${excluded.size})"
}

/** How [excluded][TunShape.excluded] reaches the builder. */
internal sealed interface RoutePlan {
    /** API 33+: both default routes, then `Builder.excludeRoute` per range. */
    data class Exclude(
        val ranges: List<CidrRange>,
    ) : RoutePlan {
        override fun toString(): String = "Exclude(${ranges.size})"
    }

    /** Below API 33: these routes instead of the default ones, the complement of the excluded ranges. */
    data class Add(
        val routes: List<CidrRange>,
    ) : RoutePlan {
        override fun toString(): String = "Add(${routes.size})"
    }
}

/** `Build.VERSION_CODES.TIRAMISU`, where `Builder.excludeRoute(IpPrefix)` arrives. */
internal const val EXCLUDE_ROUTE_MIN_SDK = 33

private const val LOOPBACK_V4_PREFIX = 8
private const val SINGLE_V6_PREFIX = 128
private const val V4_MAPPED_PREFIX = 96
private val V4_UNROUTABLE = listOf(CidrRange("127.0.0.0", LOOPBACK_V4_PREFIX, false))
private val V6_UNROUTABLE =
    listOf(CidrRange("::1", SINGLE_V6_PREFIX, true), CidrRange("::ffff:0:0", V4_MAPPED_PREFIX, true))
private val UNROUTABLE = V4_UNROUTABLE + V6_UNROUTABLE

/**
 * The route plan for [excluded] on [sdkInt] (M9-E spec §4).
 *
 * Loopback (`127.0.0.0/8`, `::1`) and the v4-mapped block (`::ffff:0:0/96`) are never handed to the
 * builder: `addRoute`/`excludeRoute` throw on a loopback address, and a v4-mapped literal parses as an
 * `Inet4Address` whose IPv6 prefix length fails the IPv4 bound. None of them is ever routed through a
 * VPN, so a range inside one is dropped as a no-op, and below 33 a family with any range also leaves
 * them out of its routes — otherwise `126.0.0.0/8`'s complement would name `127.0.0.0/8`.
 */
internal fun routePlanFor(
    excluded: List<CidrRange>,
    sdkInt: Int,
): RoutePlan {
    val routable = excluded.filter { range -> UNROUTABLE.none { ExcludedRoutes.covers(it, range) } }
    if (sdkInt >= EXCLUDE_ROUTE_MIN_SDK) return RoutePlan.Exclude(routable)
    val v4 = routable.filter { !it.isV6 }
    val v6 = routable.filter { it.isV6 }
    return RoutePlan.Add(
        ExcludedRoutes.complement(if (v4.isEmpty()) v4 else v4 + V4_UNROUTABLE, v6 = false) +
            ExcludedRoutes.complement(if (v6.isEmpty()) v6 else v6 + V6_UNROUTABLE, v6 = true),
    )
}

/**
 * Hands [plan] to a builder. The builder is two lambdas because `VpnService.Builder` cannot be
 * constructed in a JVM test, the same reason [addDnsServerOrFallback] takes one.
 */
internal fun applyRoutePlan(
    plan: RoutePlan,
    addRoute: (address: String, prefixLength: Int) -> Unit,
    excludeRoute: (CidrRange) -> Unit,
) {
    when (plan) {
        is RoutePlan.Exclude -> {
            addRoute("0.0.0.0", 0)
            addRoute("::", 0)
            plan.ranges.forEach(excludeRoute)
        }
        is RoutePlan.Add -> plan.routes.forEach { addRoute(it.address, it.prefixLength) }
    }
}

/**
 * Whether a restart that keeps the live TUN still matches the shape the next core plans for it
 * (ARCHITECTURE.md §5.2, lever 1; M9-E spec §4, §7.1). Replaces the advertised-resolver-only check.
 *
 * Keeping the interface never re-runs `Builder.establish()`, so it goes on with the MTU, routes and
 * resolver it was built with. Equal shapes only. [live] null means nothing is recorded for the
 * interface: unknown, not unchanged, so the caller rebuilds. Guessing wrong here costs one rebuild;
 * guessing wrong the other way leaks DNS or tunnels a range the user excluded.
 */
internal fun retainedTunKeepsShape(
    live: TunShape?,
    next: TunShape,
): Boolean = live != null && live == next

/**
 * The DNS servers an excluded range must not cover (M9-E spec §4): [advertisedDns], [fallbackDns]
 * (what [addDnsServerOrFallback] advertises instead if the builder rejects the planned literal), and
 * every plain resolver in [dnsPlan] that is an address literal. A DoH endpoint is a hostname and is
 * skipped. The fallback is included even when the planned literal will be accepted: which one the
 * builder takes is only known after the routes are planned, and dropping a range needlessly costs
 * less than a resolver outside the tunnel.
 */
internal fun sessionDnsAddresses(
    dnsPlan: DnsPlan?,
    advertisedDns: String,
    fallbackDns: String,
): List<String> =
    (
        listOf(advertisedDns, fallbackDns) +
            dnsPlan?.servers.orEmpty().map { it.address }.filter(DnsValidation::isAddressLiteral)
    ).distinct()

/** The shape a session resolved to [tuning] plans, advertising [advertisedDns]. */
internal fun tunShapeFor(
    tuning: ResolvedTuning,
    advertisedDns: String,
): TunShape = TunShape(tuning.tunMtu.value, advertisedDns, tuning.excludedRoutes.value)
