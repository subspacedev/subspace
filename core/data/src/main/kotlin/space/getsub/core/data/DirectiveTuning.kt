// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import space.getsub.core.model.CidrRange
import space.getsub.core.model.ExcludeRoutesUserSetting
import space.getsub.core.model.ExcludedRoutes
import space.getsub.core.model.MuxRules
import space.getsub.core.model.MuxSpec
import space.getsub.core.model.MuxUserSettings
import space.getsub.core.model.SniffingUserSetting
import space.getsub.core.model.TunMtu
import space.getsub.core.model.TunMtuUserSetting
import space.getsub.core.model.TuningChecked
import space.getsub.core.model.TuningOrigin
import space.getsub.core.model.TuningProblem

/**
 * One resolved tuning value. [ignored] is why a value someone set is not the one in effect (the
 * user's invalid entry, the provider's invalid or unconsented directive, or a part of a list that
 * was dropped), shown in server or subscription details.
 *
 * ARCHITECTURE.md §5.6: [toString] leaves [value] out, since it can be excluded ranges or an MTU.
 */
public data class TuningValue<out T>(
    val value: T,
    val origin: TuningOrigin,
    val ignored: TuningProblem? = null,
) {
    override fun toString(): String = "TuningValue(origin=$origin, ignored=$ignored)"
}

/** The tuning a row's session runs with (M9-E spec §1–§4, §7.1). */
public data class ResolvedTuning(
    val sniffing: TuningValue<Boolean>,
    /** Null means mux off. Whether the server can use it is [space.getsub.core.model.MuxApplicability]'s call. */
    val mux: TuningValue<MuxSpec?>,
    val tunMtu: TuningValue<Int>,
    val excludedRoutes: TuningValue<List<CidrRange>>,
)

/** The user's four tuning settings, as [SettingsRepository] stores them. */
public data class TuningUserInputs(
    val sniffing: SniffingUserSetting,
    val mux: MuxUserSettings,
    val mtu: TunMtuUserSetting,
    val excludeRoutes: ExcludeRoutesUserSetting,
)

private const val SNIFFING_ENABLE = "sniffing-enable"
internal const val MUX_ENABLE = "mux-enable"
private const val MUX_TCP = "mux-tcp-connections"
private const val MUX_XUDP = "mux-xudp-connections"
private const val MUX_QUIC = "mux-quic"
internal const val TUN_MTU = "xray-tun-mtu"

/**
 * Every directive key this file reads; the resolver fetches exactly these. The provider's ranges
 * themselves arrive through [RouteExclusionStatus]; the two route keys are read here only to say
 * why an offered list that does not parse was ignored.
 */
public val TUNING_DIRECTIVE_KEYS: List<String> =
    listOf(
        SNIFFING_ENABLE,
        MUX_ENABLE,
        MUX_TCP,
        MUX_XUDP,
        MUX_QUIC,
        TUN_MTU,
        DIRECTIVE_EXCLUDE_ROUTES,
        DIRECTIVE_EXCLUDE_ROUTES_SET,
    )

/**
 * M9-E spec §1: your setting, when its switch is On, wins (origin [TuningOrigin.Yours]). Otherwise a
 * valid provider directive applies ([TuningOrigin.Subscription]). Otherwise the app default
 * ([TuningOrigin.Default]). An invalid value, yours or the provider's, gives the default with the
 * reason in [TuningValue.ignored]. [TuningOrigin.Config] is never produced here: only the raw-path
 * report knows a config kept its own block.
 *
 * The provider's excluded ranges come from [routeTrust]: they apply only when the user allowed the
 * current list ([RouteExclusionStatus.trust] already reads Unasked for an answer given to an older
 * list), otherwise nothing applies and [TuningProblem.NeedsConsent] (unanswered) or
 * [TuningProblem.Declined] (Don't allow) says why.
 *
 * Problem indices: [TuningProblem.RangeTooWide] indexes the parsed user list or
 * [RouteExclusionStatus.offered]. The DNS check is not done here; see [dropRangesCoveringDns] in TuningResolver.kt.
 */
public fun resolveTuning(
    user: TuningUserInputs,
    directives: Map<String, String?>,
    routeTrust: RouteExclusionStatus,
): ResolvedTuning =
    ResolvedTuning(
        sniffing = sniffing(user.sniffing, directives),
        mux = mux(user.mux, directives),
        tunMtu = tunMtu(user.mtu, directives),
        excludedRoutes = excludedRoutes(user.excludeRoutes, directives, routeTrust),
    )

private fun sniffing(
    user: SniffingUserSetting,
    d: Map<String, String?>,
): TuningValue<Boolean> =
    when {
        user.override -> TuningValue(user.enabled, TuningOrigin.Yours)
        else ->
            directiveBool(d[SNIFFING_ENABLE])?.let { TuningValue(it, TuningOrigin.Subscription) }
                ?: TuningValue(SniffingUserSetting.DEFAULT.enabled, TuningOrigin.Default)
    }

private fun mux(
    user: MuxUserSettings,
    d: Map<String, String?>,
): TuningValue<MuxSpec?> =
    when {
        user.override && !user.enabled -> TuningValue(null, TuningOrigin.Yours)
        user.override ->
            checkedValue(MuxRules.check(user.tcpConnections, user.xudpConnections, user.quic), TuningOrigin.Yours, null)
        directiveBool(d[MUX_ENABLE]) == null -> TuningValue(null, TuningOrigin.Default)
        directiveBool(d[MUX_ENABLE]) == false -> TuningValue(null, TuningOrigin.Subscription)
        else -> {
            val defaults = MuxUserSettings.DEFAULT
            val tcp = d[MUX_TCP].orDefault(defaults.tcpConnections)
            val xudp = d[MUX_XUDP].orDefault(defaults.xudpConnections)
            val quic = d[MUX_QUIC].orDefault(defaults.quic).trim()
            // MuxRules checks tcp, then xudp, then quic, so the first field that fails on its own is the bad one.
            val badKey =
                when {
                    MuxRules.check(tcp, defaults.xudpConnections, defaults.quic) is TuningChecked.Invalid -> MUX_TCP
                    MuxRules.check(defaults.tcpConnections, xudp, defaults.quic) is TuningChecked.Invalid -> MUX_XUDP
                    else -> MUX_QUIC
                }
            checkedValue(MuxRules.check(tcp, xudp, quic), TuningOrigin.Subscription, null, badKey)
        }
    }

private fun tunMtu(
    user: TunMtuUserSetting,
    d: Map<String, String?>,
): TuningValue<Int> {
    val provided = d[TUN_MTU]?.takeIf(String::isNotBlank)
    return when {
        user.override -> checkedValue(TunMtu.check(user.value), TuningOrigin.Yours, TunMtu.DEFAULT)
        provided == null -> TuningValue(TunMtu.DEFAULT, TuningOrigin.Default)
        else -> checkedValue(TunMtu.check(provided), TuningOrigin.Subscription, TunMtu.DEFAULT, TUN_MTU)
    }
}

private fun excludedRoutes(
    user: ExcludeRoutesUserSetting,
    d: Map<String, String?>,
    status: RouteExclusionStatus,
): TuningValue<List<CidrRange>> {
    val none = emptyList<CidrRange>()
    return when {
        user.override ->
            when (val checked = ExcludedRoutes.parse(user.ranges)) {
                is TuningChecked.Ok -> withoutTooWide(checked.value)
                is TuningChecked.Invalid -> TuningValue(none, TuningOrigin.Default, checked.problem)
            }
        status.offered.isEmpty() -> {
            // The status parses the same effective list; empty with a value present means it did not parse.
            val key =
                listOf(DIRECTIVE_EXCLUDE_ROUTES_SET, DIRECTIVE_EXCLUDE_ROUTES).firstOrNull { !d[it].isNullOrBlank() }
            TuningValue(none, TuningOrigin.Default, key?.let { TuningProblem.BadValue(it) })
        }
        status.applicable.isEmpty() -> TuningValue(none, TuningOrigin.Default, firstTooWide(status.offered))
        status.trust == RouteExclusionTrust.Denied -> TuningValue(none, TuningOrigin.Default, TuningProblem.Declined)
        status.trust != RouteExclusionTrust.Allowed ->
            TuningValue(none, TuningOrigin.Default, TuningProblem.NeedsConsent)
        else -> TuningValue(status.applicable, TuningOrigin.Subscription, firstTooWide(status.offered))
    }
}

/** Your list less its `/0` ranges, which never apply; the first one dropped is the reason shown. */
private fun withoutTooWide(parsed: List<CidrRange>): TuningValue<List<CidrRange>> {
    val tooWide = ExcludedRoutes.validate(parsed, emptyList()).filterIsInstance<TuningProblem.RangeTooWide>()
    val dropped = tooWide.mapTo(mutableSetOf()) { it.index }
    return TuningValue(parsed.filterIndexed { i, _ -> i !in dropped }, TuningOrigin.Yours, tooWide.firstOrNull())
}

private fun firstTooWide(ranges: List<CidrRange>): TuningProblem? =
    ExcludedRoutes.validate(ranges, emptyList()).firstOrNull { it is TuningProblem.RangeTooWide }

/**
 * [checked] as a value from [origin], or [fallback] from [TuningOrigin.Default] with the reason.
 * With [directive] set, a [TuningProblem.BadValue] or [TuningProblem.OutOfRange] names that
 * directive, so subscription details point at the key the provider can fix rather than the model's
 * field name.
 */
private fun <T> checkedValue(
    checked: TuningChecked<T>,
    origin: TuningOrigin,
    fallback: T,
    directive: String? = null,
): TuningValue<T> =
    when (checked) {
        is TuningChecked.Ok -> TuningValue(checked.value, origin)
        is TuningChecked.Invalid -> {
            val problem = checked.problem
            val shown =
                when {
                    directive == null -> problem
                    problem is TuningProblem.BadValue -> TuningProblem.BadValue(directive)
                    problem is TuningProblem.OutOfRange -> problem.copy(key = directive)
                    else -> problem
                }
            TuningValue(fallback, TuningOrigin.Default, shown)
        }
    }

/** ARCHITECTURE.md §A.1: `true`/`1` enable, any other non-empty value disables, blank or absent is not set. */
private fun directiveBool(value: String?): Boolean? = value?.takeIf(String::isNotBlank)?.let(::isDirectiveEnabled)

private fun String?.orDefault(default: String): String = this?.takeIf(String::isNotBlank) ?: default
