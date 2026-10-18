// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import kotlinx.coroutines.flow.first
import space.getsub.core.model.ExcludeRoutesUserSetting
import space.getsub.core.model.ExcludedRoutes
import space.getsub.core.model.MuxUserSettings
import space.getsub.core.model.SniffingUserSetting
import space.getsub.core.model.TunMtuUserSetting
import space.getsub.core.model.TuningProblem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place a profile row's sniffing, mux, TUN MTU and excluded routes are decided (M9-E spec
 * §1). Directives are read only from the subscription that delivered the row (ARCHITECTURE.md §A.1);
 * a manual row sees your settings and the defaults.
 */
@Singleton
public class TuningResolver
@Inject
internal constructor(
    private val settings: SettingsRepository,
    private val subscriptions: SubscriptionRepository,
    private val profiles: ProfileRepository,
) {
    /** What the config writers and the editor report get. No DNS check: see [forSession]. */
    public suspend fun forConfig(rowId: Long): ResolvedTuning =
        resolve(
            rowId,
            TuningUserInputs(
                sniffing = settings.sniffing.first(),
                mux = settings.mux.first(),
                mtu = settings.tunMtu.first(),
                excludeRoutes = settings.excludeRoutes.first(),
            ),
        )

    /**
     * What [rowId] gets with every override of yours off: the subscription's value, else the default.
     * The Settings → Tunnel page shows it under a switch that is off ("From your subscription: …").
     */
    public suspend fun withoutYourOverrides(rowId: Long): ResolvedTuning =
        resolve(
            rowId,
            TuningUserInputs(
                sniffing = SniffingUserSetting.DEFAULT,
                mux = MuxUserSettings.DEFAULT,
                mtu = TunMtuUserSetting.DEFAULT,
                excludeRoutes = ExcludeRoutesUserSetting.DEFAULT,
            ),
        )

    private suspend fun resolve(
        rowId: Long,
        user: TuningUserInputs,
    ): ResolvedTuning {
        val subscriptionId = profiles.profile(rowId)?.let { subscriptions.subscriptionIdForGroup(it.groupId) }
        val directives = subscriptionId?.let { directives(it) }.orEmpty()
        val routes = subscriptionId?.let { subscriptions.routeExclusionStatus(it).first() } ?: RouteExclusionStatus.NONE
        return resolveTuning(user, directives, routes)
    }

    /**
     * What server details show for [rowId] (M9-E spec §1, §3): [forConfig]'s values, with a raw row
     * that runs as written read from its stored bytes ([tuningReport]), so a config's own kept block
     * reads as [space.getsub.core.model.TuningOrigin.Config] and mux reads as used only where it runs.
     */
    public suspend fun report(rowId: Long): TuningReport = tuningReport(profiles.profile(rowId), forConfig(rowId))

    /**
     * [forConfig] for a session about to start or rebuild, with every excluded range that covers one
     * of [dnsAddresses] dropped for this session ([dropRangesCoveringDns]).
     */
    public suspend fun forSession(
        rowId: Long,
        dnsAddresses: List<String>,
    ): ResolvedTuning = dropRangesCoveringDns(forConfig(rowId), dnsAddresses)

    // Through `effective`, so a user's per-subscription pin of a directive is honoured as everywhere else.
    private suspend fun directives(subscriptionId: Long): Map<String, String?> =
        TUNING_DIRECTIVE_KEYS.associateWith { key -> subscriptions.effective(subscriptionId, key, null).value }
}

/**
 * M9-E spec §4: the connect-time check. Drops every excluded range that contains one of
 * [dnsAddresses] (the resolver the TUN advertises and the DNS plan's plain resolvers), since a
 * query to it would leave outside the tunnel. Applies to your ranges and the provider's alike.
 *
 * [TuningValue.ignored] keeps one problem only: the one already there, else the first dropped
 * range's [TuningProblem.RangeCoversDns], indexed into [resolved]'s list before the drop.
 */
public fun dropRangesCoveringDns(
    resolved: ResolvedTuning,
    dnsAddresses: List<String>,
): ResolvedTuning {
    val routes = resolved.excludedRoutes
    val covering =
        ExcludedRoutes
            .validate(routes.value, dnsAddresses)
            .filterIsInstance<TuningProblem.RangeCoversDns>()
    if (covering.isEmpty()) return resolved
    val dropped = covering.mapTo(mutableSetOf()) { it.index }
    return resolved.copy(
        excludedRoutes =
        routes.copy(
            value = routes.value.filterIndexed { index, _ -> index !in dropped },
            ignored = routes.ignored ?: covering.first(),
        ),
    )
}
