// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import space.getsub.core.model.ExcludeRoutesUserSetting
import space.getsub.core.model.MuxUserSettings
import space.getsub.core.model.SniffingUserSetting
import space.getsub.core.model.TunMtuUserSetting
import space.getsub.core.model.TuningOrigin
import space.getsub.core.model.TuningProblem

/**
 * A provider tuning directive that is not applied, or only partly, and why: one line in subscription
 * details (M9-E spec §3, §4, §7.1).
 *
 * @property key the directive the provider can fix, e.g. `xray-tun-mtu` or `mux-quic`.
 * @property partial part of the value applies: an allowed list with some ranges dropped.
 */
public data class TuningDirectiveIssue(
    val key: String,
    val problem: TuningProblem,
    val partial: Boolean = false,
)

/**
 * Why a subscription's mux, TUN MTU and excluded-routes directives are not (fully) applied, resolved
 * as if every switch of yours were off, as [ObfuscationResolver.directiveIssues] does for masks.
 *
 * @property excludedRoutesApply the provider's ranges apply (allowed, at least one usable). Each one
 *   is still dropped at connect if it covers the DNS server in use, which only `:bg` knows.
 */
public data class TuningDirectiveIssues(
    val mux: TuningDirectiveIssue? = null,
    val tunMtu: TuningDirectiveIssue? = null,
    val excludedRoutes: TuningDirectiveIssue? = null,
    val excludedRoutesApply: Boolean = false,
) {
    public companion object {
        public val NONE: TuningDirectiveIssues = TuningDirectiveIssues()
    }
}

private val NO_OVERRIDES =
    TuningUserInputs(
        sniffing = SniffingUserSetting.DEFAULT,
        mux = MuxUserSettings.DEFAULT,
        mtu = TunMtuUserSetting.DEFAULT,
        excludeRoutes = ExcludeRoutesUserSetting.DEFAULT,
    )

/** [resolveTuning] with none of your overrides, read for what subscription details show. */
public fun tuningDirectiveIssues(
    directives: Map<String, String?>,
    routeTrust: RouteExclusionStatus,
): TuningDirectiveIssues {
    val resolved = resolveTuning(NO_OVERRIDES, directives, routeTrust)
    val routesKey =
        listOf(DIRECTIVE_EXCLUDE_ROUTES_SET, DIRECTIVE_EXCLUDE_ROUTES).firstOrNull { !directives[it].isNullOrBlank() }
            ?: DIRECTIVE_EXCLUDE_ROUTES
    val routes = resolved.excludedRoutes
    return TuningDirectiveIssues(
        mux = resolved.mux.ignored?.let { TuningDirectiveIssue(it.directiveKey() ?: MUX_ENABLE, it) },
        tunMtu = resolved.tunMtu.ignored?.let { TuningDirectiveIssue(TUN_MTU, it) },
        excludedRoutes =
        routes.ignored?.let {
            val partial = routes.origin == TuningOrigin.Subscription
            TuningDirectiveIssue(it.directiveKey() ?: routesKey, it, partial)
        },
        excludedRoutesApply = routes.origin == TuningOrigin.Subscription && routes.value.isNotEmpty(),
    )
}

private fun TuningProblem.directiveKey(): String? =
    when (this) {
        is TuningProblem.BadValue -> key
        is TuningProblem.OutOfRange -> key
        else -> null
    }
