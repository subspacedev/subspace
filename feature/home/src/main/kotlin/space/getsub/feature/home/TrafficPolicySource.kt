// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import space.getsub.core.data.PerAppRepository
import space.getsub.core.data.RoutingRepository
import space.getsub.core.data.SettingsRepository
import space.getsub.core.model.PerAppSelection
import javax.inject.Inject
import javax.inject.Singleton

/** What Home's Routing and Apps chips say (M8.6 spec §4.1, fix A9). Read only; chosen elsewhere. */
internal interface TrafficPolicySource {
    /** The active rule set's name, or null when none is active or the id names no row. Displayed, never logged. */
    val activeRuleSetName: Flow<String?>

    /** The **effective** per-app selection, the one `:service` applies (M8.5 spec §6 #13). */
    val perApp: Flow<PerAppSelection>
}

@Singleton
internal class BoundTrafficPolicySource
@Inject
constructor(
    settings: SettingsRepository,
    routing: RoutingRepository,
    perAppRepository: PerAppRepository,
) : TrafficPolicySource {
    override val activeRuleSetName: Flow<String?> =
        combine(settings.activeRoutingRuleSetId, routing.observeAll()) { id, sets ->
            sets.firstOrNull { it.id == id }?.name
        }.distinctUntilChanged()

    override val perApp: Flow<PerAppSelection> = perAppRepository.selection.distinctUntilChanged()
}
