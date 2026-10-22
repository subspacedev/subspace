// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import space.getsub.core.data.ProfileRepository
import space.getsub.core.data.SettingsRepository
import space.getsub.core.data.StoredProfile
import space.getsub.core.data.SubscriptionRepository
import space.getsub.core.model.subscription.ProviderQuota
import space.getsub.core.model.subscription.SubscriptionInfo
import space.getsub.core.model.subscription.subscriptionInfo
import space.getsub.core.parser.directive.UserInfo
import space.getsub.core.parser.directive.parseUserInfo
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The stored-profile half of [HomeState], as this screen needs to see it.
 *
 * Both [ProfileRepository] and [SettingsRepository] have `internal`
 * constructors scoped to `:core:data` (§3 keeps settings behind a typed
 * repository, not a key/value table any module can poke) — this module
 * cannot build a real instance of either to test against. Mirrors
 * [TunnelConnection]'s own reason for existing: the interface lets
 * [HomeViewModel] be exercised with a plain fake, and [BoundActiveProfileSource]
 * is the one place that touches the real repositories.
 */
internal interface ActiveProfileSource {
    /**
     * Whether any profile exists in any group, active or not. `false` only
     * for a genuinely empty store — the case that should point the user at
     * import rather than at a list with nothing in it.
     */
    val hasAnyProfile: Flow<Boolean>

    /** The profile [SettingsRepository.activeProfileId] currently names, or `null`. */
    val activeProfile: Flow<StoredProfile?>

    /**
     * What the active profile's subscription says, or `null` for a manual server or when nothing
     * is selected (M10-B spec §3.3). Only the limit, the expiry and the renew link are read from it.
     */
    val activeSubscription: Flow<SubscriptionInfo?>
}

@Singleton
internal class BoundActiveProfileSource
@Inject
constructor(
    profileRepository: ProfileRepository,
    settingsRepository: SettingsRepository,
    subscriptionRepository: SubscriptionRepository,
) : ActiveProfileSource {
    private val allProfiles: Flow<List<StoredProfile>> =
        profileRepository.observeGroups().map { groups -> groups.flatMap { it.profiles } }

    override val hasAnyProfile: Flow<Boolean> =
        allProfiles.map { it.isNotEmpty() }.distinctUntilChanged()

    // Finding the row whose id matches the stored setting is not the
    // firstOrNull() shortcut this task retires — that took whichever
    // profile happened to be first; this looks up the one specific row the
    // user chose, by id, and is null when that id names none (a deleted
    // profile that is still recorded as active).
    override val activeProfile: Flow<StoredProfile?> =
        combine(allProfiles, settingsRepository.activeProfileId) { profiles, activeId ->
            profiles.firstOrNull { it.id == activeId }
        }

    // A subscription owns one group, so the active profile's group names its subscription. A
    // manual group has none, which is the "nothing" case rather than an error. The userinfo is
    // the effective value (a pin, else the provider's), the same one the Servers card reads.
    @OptIn(ExperimentalCoroutinesApi::class)
    override val activeSubscription: Flow<SubscriptionInfo?> =
        combine(
            activeProfile.map { it?.groupId }.distinctUntilChanged(),
            subscriptionRepository.observeSubscriptions(),
        ) { groupId, subscriptions ->
            subscriptions.firstOrNull { it.groupId == groupId }?.id
        }.distinctUntilChanged().flatMapLatest { subscriptionId ->
            if (subscriptionId == null) {
                flowOf(null)
            } else {
                combine(
                    subscriptionRepository.observeEffective(subscriptionId, KEY_USERINFO, default = null),
                    subscriptionRepository.observeEffective(subscriptionId, KEY_RENEW_LINK, default = null),
                ) { userInfo, renewLink ->
                    subscriptionInfo(
                        directives = listOfNotNull(renewLink.value?.let { KEY_RENEW_LINK to it }).toMap(),
                        userInfo = userInfo.value?.let(::parseUserInfo)?.toQuota(),
                        today = LocalDate.now(),
                        zone = ZoneId.systemDefault(),
                    )
                }
            }
        }
}

private const val KEY_USERINFO = "subscription-userinfo"
private const val KEY_RENEW_LINK = "sub-expire-button-link"

private fun UserInfo.toQuota() = ProviderQuota(upload, download, total, expiresAtEpochSeconds)
