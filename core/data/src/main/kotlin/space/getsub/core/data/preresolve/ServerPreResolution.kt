// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.preresolve

import space.getsub.core.data.ProfileRepository
import space.getsub.core.data.ResolverTrust
import space.getsub.core.data.SettingsRepository
import space.getsub.core.data.SubscriptionRepository
import space.getsub.core.data.resolverTrustOf
import space.getsub.core.data.sync.FetchDecisions
import javax.inject.Inject
import javax.inject.Singleton

/** M9-C spec §1.1 at connect: reads the setting, the row's subscription and its grant fresh every time. */
@Singleton
public class ServerPreResolution
@Inject
internal constructor(
    private val settings: SettingsRepository,
    private val subscriptions: SubscriptionRepository,
    private val profiles: ProfileRepository,
) {
    public suspend fun decide(rowId: Long): PreResolveDecision {
        val user = settings.preResolveSettingsNow()
        val fragment = FetchDecisions.fetchFragment(settings.fragmentSettingsNow(), linkFragment = null)
        val groupId = profiles.profile(rowId)?.groupId
        val subscriptionId = groupId?.let { subscriptions.subscriptionIdForGroup(it) }
        val provider =
            subscriptionId?.let { id ->
                providerResolverOf(
                    PRERESOLVE_DIRECTIVE_KEYS.associateWith { subscriptions.effective(id, it, null).value },
                )
            } ?: ProviderResolver.Absent
        val row = subscriptionId?.let { subscriptions.subscriptionRow(it) }
        return PreResolvePolicy.decide(
            user,
            fragment,
            provider,
            row?.let { resolverTrustOf(it.resolverTrust) } ?: ResolverTrust.Unasked,
            row?.resolverTrustHost,
        )
    }
}
