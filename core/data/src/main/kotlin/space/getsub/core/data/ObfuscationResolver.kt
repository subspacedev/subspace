// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.NoiseSettings
import space.getsub.core.model.Obfuscation
import space.getsub.core.model.ObfuscationProblem
import space.getsub.core.parser.ObfuscationDetection
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

    public suspend fun report(rowId: Long): ObfuscationReport? {
        val profile = profiles.profile(rowId) ?: return null
        val resolved = resolveFor(profile.groupId)
        val own = profile.rawJson?.let(ObfuscationDetection::configOwn)
        return ObfuscationReport(
            fragment = maskReport(resolved.fragment, ownInConfig = own?.fragment == true),
            noise = maskReport(resolved.noise, ownInConfig = own?.noise == true),
        )
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

    private fun maskReport(
        resolution: MaskResolution<*>,
        ownInConfig: Boolean,
    ): MaskReport =
        when {
            resolution is MaskResolution.Resolved && resolution.source == ObfuscationSource.User ->
                MaskReport(ObfuscationOrigin.Yours, null)
            // A config's own mask beats a directive (M9-A spec §1.1).
            ownInConfig -> MaskReport(ObfuscationOrigin.Config, null)
            resolution is MaskResolution.Resolved -> MaskReport(ObfuscationOrigin.Subscription, null)
            resolution is MaskResolution.Ignored -> MaskReport(ObfuscationOrigin.None, resolution.problem)
            else -> MaskReport(ObfuscationOrigin.None, null)
        }
}
