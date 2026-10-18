// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.NoiseSettings
import space.getsub.core.model.Obfuscation
import space.getsub.core.model.ObfuscationApplicability
import space.getsub.core.model.ObfuscationProblem
import space.getsub.core.parser.MaskFate
import space.getsub.core.parser.MaskOffer
import space.getsub.core.parser.ObfuscationDetection
import space.getsub.core.parser.OutboundPlan
import javax.inject.Inject
import javax.inject.Singleton

/** Where a server's fragmentation or noise comes from, as the editor shows it (M9-A spec §2.4). */
public enum class ObfuscationOrigin { Yours, Subscription, Config, None }

public data class MaskReport(val origin: ObfuscationOrigin, val ignored: ObfuscationProblem?)

public data class ObfuscationReport(val fragment: MaskReport, val noise: MaskReport)

/**
 * The one place a profile row's fragment and noise are decided (M9-A spec §3.1). The service asks it
 * for the config at connect and probe time; the editor asks it for the report.
 */
@Singleton
public class ObfuscationResolver
@Inject
internal constructor(
    private val settings: SettingsRepository,
    private val subscriptions: SubscriptionRepository,
    private val profiles: ProfileRepository,
) {
    public suspend fun forConfig(rowId: Long): Obfuscation? = resolve(rowId)?.toObfuscation()

    /**
     * What the row's config will actually run, per mask (M9-A spec §2.4).
     *
     * A row that does not run as written ([StoredProfile.runsAsWritten] false: a typed row, or a
     * raw row running its typed projection) never runs a config's own mask, so it reports straight
     * from the resolution. A row that does is described by [ObfuscationDetection.plan], the same
     * decision the injector executes, so this cannot claim a mask the tunnel will not run.
     */
    public suspend fun report(rowId: Long): ObfuscationReport? {
        val profile = profiles.profile(rowId) ?: return null
        return obfuscationReport(profile, resolveFor(profile.groupId))
    }

    /** The subscription's directives resolved as if both settings were Off, for subscription detail. */
    public suspend fun directiveIssues(subscriptionId: Long): ResolvedObfuscation =
        resolveObfuscation(FragmentSettings.DEFAULT, NoiseSettings.DEFAULT, directives(subscriptionId))

    private suspend fun resolve(rowId: Long): ResolvedObfuscation? =
        profiles.profile(rowId)?.let { resolveFor(it.groupId) }

    private suspend fun resolveFor(groupId: Long): ResolvedObfuscation {
        val subscriptionId = subscriptions.subscriptionIdForGroup(groupId)
        return resolveObfuscation(
            settings.fragmentSettingsNow(),
            settings.noiseSettingsNow(),
            subscriptionId?.let { directives(it) }.orEmpty(),
        )
    }

    // Through `effective`, so a user's per-subscription pin of a directive is honoured as everywhere else.
    private suspend fun directives(subscriptionId: Long): Map<String, String?> =
        OBFUSCATION_DIRECTIVE_KEYS.associateWith { key -> subscriptions.effective(subscriptionId, key, null).value }
}

/**
 * The report for [profile] given its [resolved] masks; pure so it is testable without a database.
 *
 * A row that runs as written is described by [ObfuscationDetection.plan], the same decision the
 * injector executes. Any other row (typed, or raw running its projection) never runs a config's own
 * mask, so it reports straight from the resolution, except where [ObfuscationApplicability] says the
 * generator does not write the mask: no fragment (a TCP mask) for Hysteria2's QUIC (M9-D1 spec §4),
 * WireGuard or mKCP, and no noise (a UDP mask) for the HTTP proxy or HTTPUpgrade (M9-D2 research §5.3).
 * Such a mask reads as not applied, exactly as the raw path's plan makes it.
 */
internal fun obfuscationReport(
    profile: StoredProfile,
    resolved: ResolvedObfuscation,
): ObfuscationReport {
    val rawJson = profile.rawJson?.takeIf { profile.runsAsWritten }
    if (rawJson == null) {
        val outbound = profile.outbound
        val fragment = outbound == null || ObfuscationApplicability.fragmentApplies(outbound)
        val noise = outbound == null || ObfuscationApplicability.noiseApplies(outbound)
        return ObfuscationReport(
            if (fragment) typedReport(resolved.fragment) else rawReport(resolved.fragment, listOf(MaskFate.Nothing)),
            if (noise) typedReport(resolved.noise) else rawReport(resolved.noise, listOf(MaskFate.Nothing)),
        )
    }
    val plans = ObfuscationDetection.plan(rawJson, resolved.fragment.offer(), resolved.noise.offer())
    return ObfuscationReport(
        fragment = rawReport(resolved.fragment, plans.map(OutboundPlan::fragment)),
        noise = rawReport(resolved.noise, plans.map(OutboundPlan::noise)),
    )
}

private fun MaskResolution<*>.offer(): MaskOffer? =
    (this as? MaskResolution.Resolved)?.let { MaskOffer(replaceOwn = it.source == ObfuscationSource.User) }

private fun typedReport(resolution: MaskResolution<*>): MaskReport =
    when (resolution) {
        is MaskResolution.Resolved ->
            MaskReport(
                if (resolution.source == ObfuscationSource.User) {
                    ObfuscationOrigin.Yours
                } else {
                    ObfuscationOrigin.Subscription
                },
                null,
            )
        is MaskResolution.Ignored -> MaskReport(ObfuscationOrigin.None, resolution.problem)
        MaskResolution.Absent -> MaskReport(ObfuscationOrigin.None, null)
    }

/**
 * One mask across a raw config's outermost outbounds ([fates], one per outbound).
 *
 * Yours if any outbound gets the user's mask. Otherwise Config if any outbound keeps its own,
 * even when others get the directive: a config's own mask beats a directive (M9-A spec §1.1),
 * so a mixed config is described by what the provider wrote. Otherwise Subscription if any
 * outbound gets the directive, else None (with the reason when the directive was ignored).
 * A config with no eligible outbound plans nothing, so it is None.
 */
private fun rawReport(
    resolution: MaskResolution<*>,
    fates: List<MaskFate>,
): MaskReport {
    val user = resolution is MaskResolution.Resolved && resolution.source == ObfuscationSource.User
    return when {
        MaskFate.Ours in fates && user -> MaskReport(ObfuscationOrigin.Yours, null)
        MaskFate.Own in fates -> MaskReport(ObfuscationOrigin.Config, null)
        MaskFate.Ours in fates -> MaskReport(ObfuscationOrigin.Subscription, null)
        resolution is MaskResolution.Ignored -> MaskReport(ObfuscationOrigin.None, resolution.problem)
        else -> MaskReport(ObfuscationOrigin.None, null)
    }
}
