// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.subscription

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import space.getsub.core.data.AddedSubscription
import space.getsub.core.data.EffectiveValue
import space.getsub.core.data.MaskResolution
import space.getsub.core.data.ObfuscationReport
import space.getsub.core.data.ProfileGroup
import space.getsub.core.data.RedirectStatus
import space.getsub.core.data.RedirectTrust
import space.getsub.core.data.ResolvedObfuscation
import space.getsub.core.data.ResolverTrust
import space.getsub.core.data.RouteExclusionStatus
import space.getsub.core.data.RouteExclusionTrust
import space.getsub.core.data.StoredProfile
import space.getsub.core.data.StoredSubscription
import space.getsub.core.data.preresolve.ProviderResolver
import space.getsub.core.data.preresolve.ResolverStatus
import space.getsub.core.data.sync.SyncResult
import space.getsub.core.model.CidrRange
import space.getsub.core.model.ObfuscationProblem
import space.getsub.core.model.Outbound
import space.getsub.core.model.Profile
import space.getsub.feature.profiles.ProfileSource
import space.getsub.feature.profiles.RouteExclusionRequest

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionDetailViewModelTest {
    private class FakeProfileSource : ProfileSource {
        val subscriptions = MutableStateFlow(listOf(subscription(id = SUBSCRIPTION_ID)))
        private val groups =
            MutableStateFlow(
                listOf(ProfileGroup(id = GROUP_ID, name = "Provider", profiles = emptyList())),
            )
        private val providerValues =
            mapOf(
                KEY_INTERVAL to "6",
                KEY_TIMEOUT to "9",
                KEY_AUTO_UPDATE to "true",
                KEY_AUTO_UPDATE_OPEN to "true",
                KEY_USER_AGENT to "Provider Agent",
            )
        private val pins = MutableStateFlow<Map<String, String>>(emptyMap())
        val globalHwidEnabledState = MutableStateFlow(true)

        var pinned: Triple<Long, String, String>? = null
            private set
        var unpinned: Pair<Long, String>? = null
            private set
        var deletedId: Long? = null
            private set
        var userAgentOverride: String? = null
            private set
        var syncCalls = 0
            private set
        var syncGate: CompletableDeferred<Unit>? = null
        var deleteGate: CompletableDeferred<Unit>? = null
        val syncStarted = CompletableDeferred<Unit>()

        override fun observeGroups(query: String, protocol: String?): Flow<List<ProfileGroup>> = groups
        override val activeProfileId: StateFlow<Long?> = MutableStateFlow(null)
        override val globalHwidEnabled: StateFlow<Boolean> = globalHwidEnabledState
        override suspend fun setActiveProfile(id: Long?) = Unit
        override suspend fun renameGroup(id: Long, name: String) = Unit
        override suspend fun deleteGroup(id: Long) = Unit
        override suspend fun defaultGroupId(): Long = GROUP_ID
        override suspend fun import(profiles: List<Profile>, groupId: Long): Int = 0
        override suspend fun profile(id: Long): StoredProfile? = null
        override suspend fun rename(id: Long, name: String) = Unit
        override suspend fun move(id: Long, toGroupId: Long): Boolean = true
        override suspend fun update(id: Long, name: String, outbound: Outbound): Boolean = true
        override suspend fun addSubscription(url: String, name: String): AddedSubscription =
            AddedSubscription(SUBSCRIPTION_ID, created = true)
        override suspend fun syncSubscription(id: Long): SyncResult {
            syncCalls++
            syncStarted.complete(Unit)
            syncGate?.await()
            return SyncResult.Synced(0, 0, 0, 0, 0)
        }
        override suspend fun deleteSubscription(id: Long) {
            deleteGate?.await()
            deletedId = id
            subscriptions.value = emptyList()
        }

        override fun observeSubscriptions(): Flow<List<StoredSubscription>> = subscriptions
        override fun observeUserInfo(id: Long): Flow<String?> = MutableStateFlow(null)
        override fun observeEffective(id: Long, key: String, default: String?): Flow<EffectiveValue> =
            pins.map { currentPins ->
                val pin = currentPins[key]
                val provider = providerValues[key]
                EffectiveValue(key, pin ?: provider ?: default, provider, pin != null)
            }

        override suspend fun pin(id: Long, key: String, value: String) {
            pinned = Triple(id, key, value)
            pins.value += key to value
        }

        override suspend fun unpin(id: Long, key: String) {
            unpinned = id to key
            pins.value -= key
        }

        override suspend fun setHwidEnabled(id: Long, enabled: Boolean) = Unit
        override suspend fun setUserAgentOverride(id: Long, userAgent: String?) {
            userAgentOverride = userAgent
        }

        val redirectStatus = MutableStateFlow(RedirectStatus.NONE)
        val fragmentationOn = MutableStateFlow(false)
        val trustWrites = mutableListOf<RedirectTrust>()
        val frontingWrites = mutableListOf<Pair<String?, String?>>()
        val fragmentWrites = mutableListOf<String?>()

        override fun observeRedirectStatus(id: Long): Flow<RedirectStatus> = redirectStatus
        override val globalFragmentationOn: Flow<Boolean> get() = fragmentationOn
        override suspend fun setRedirectTrust(id: Long, trust: RedirectTrust) {
            trustWrites += trust
        }
        override suspend fun setFronting(id: Long, resolveAddress: String?, hostHeader: String?) {
            frontingWrites += resolveAddress to hostHeader
        }
        override suspend fun setLinkFragment(id: Long, fragment: String?) {
            fragmentWrites += fragment
        }

        val resolverStatus = MutableStateFlow(ResolverStatus(ProviderResolver.Absent, ResolverTrust.Unasked))
        val preResolveOn = MutableStateFlow(false)
        var lastResolverTrust: Triple<Long, ResolverTrust, String>? = null

        override fun observeResolverStatus(id: Long): Flow<ResolverStatus> = resolverStatus
        override val userPreResolveOn: Flow<Boolean> get() = preResolveOn
        override suspend fun setResolverTrust(id: Long, trust: ResolverTrust, host: String) {
            lastResolverTrust = Triple(id, trust, host)
        }

        val routeStatus = MutableStateFlow(RouteExclusionStatus.NONE)
        var lastRouteTrust: Triple<Long, RouteExclusionTrust, String>? = null

        override fun observeRouteExclusionStatus(id: Long): Flow<RouteExclusionStatus> = routeStatus
        override suspend fun setRouteExclusionTrust(id: Long, trust: RouteExclusionTrust, fingerprint: String) {
            lastRouteTrust = Triple(id, trust, fingerprint)
        }

        val directiveIssues = mutableMapOf<Long, ResolvedObfuscation>()
        override suspend fun obfuscationReport(id: Long): ObfuscationReport? = null

        /** When set, the issues follow the pins the way the real resolver follows `effective`. */
        var issuesFromPins: ((Map<String, String>) -> ResolvedObfuscation)? = null
        var issuesFailure: Exception? = null

        override suspend fun obfuscationDirectiveIssues(subscriptionId: Long): ResolvedObfuscation {
            issuesFailure?.let { throw it }
            return issuesFromPins?.invoke(pins.value)
                ?: directiveIssues[subscriptionId]
                ?: ResolvedObfuscation(MaskResolution.Absent, MaskResolution.Absent)
        }
    }

    private lateinit var source: FakeProfileSource
    private lateinit var viewModel: SubscriptionDetailViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source = FakeProfileSource()
        viewModel = SubscriptionDetailViewModel(source)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an invalid directive surfaces its reason`() =
        runTest {
            source.directiveIssues[SUBSCRIPTION_ID] =
                ResolvedObfuscation(MaskResolution.Ignored(ObfuscationProblem.LengthFromZero), MaskResolution.Absent)

            viewModel.load(SUBSCRIPTION_ID)
            advanceUntilIdle()

            viewModel.state.value.fragmentIgnored shouldBe ObfuscationProblem.LengthFromZero
            viewModel.state.value.noiseIgnored shouldBe null
        }

    @Test
    fun `directive issues are read again after a refresh completes`() =
        runTest {
            viewModel.load(SUBSCRIPTION_ID)
            advanceUntilIdle()
            viewModel.state.value.noiseIgnored shouldBe null

            source.directiveIssues[SUBSCRIPTION_ID] =
                ResolvedObfuscation(MaskResolution.Absent, MaskResolution.Ignored(ObfuscationProblem.LengthFromZero))
            viewModel.onRefreshNow()
            advanceUntilIdle()

            viewModel.state.value.noiseIgnored shouldBe ObfuscationProblem.LengthFromZero
        }

    @Test
    fun `pinning a fixing value clears the ignored reason without a refresh`() =
        runTest {
            // The provider sent two packets entries; a pin of one fixes it.
            source.issuesFromPins = { pins ->
                if (pins["fragmentation-packets"] == null) {
                    ResolvedObfuscation(
                        MaskResolution.Ignored(ObfuscationProblem.MultiplePackets),
                        MaskResolution.Absent,
                    )
                } else {
                    ResolvedObfuscation(MaskResolution.Absent, MaskResolution.Absent)
                }
            }
            viewModel.load(SUBSCRIPTION_ID)
            advanceUntilIdle()
            viewModel.state.value.fragmentIgnored shouldBe ObfuscationProblem.MultiplePackets

            viewModel.onPinRow("fragmentation-packets", "tlshello")
            advanceUntilIdle()

            source.pinned shouldBe Triple(SUBSCRIPTION_ID, "fragmentation-packets", "tlshello")
            source.syncCalls shouldBe 0
            viewModel.state.value.fragmentIgnored shouldBe null

            viewModel.onUnpinRow("fragmentation-packets")
            advanceUntilIdle()
            viewModel.state.value.fragmentIgnored shouldBe ObfuscationProblem.MultiplePackets
        }

    @Test
    fun `a failed issues read shows no ignored line and the screen still loads`() =
        runTest {
            source.issuesFailure = IllegalStateException("database closed")
            viewModel.load(SUBSCRIPTION_ID)
            advanceUntilIdle()

            viewModel.state.value.loading shouldBe false
            viewModel.state.value.fragmentIgnored shouldBe null
            viewModel.state.value.noiseIgnored shouldBe null

            // The combine survived: a later directive change is still read.
            source.issuesFailure = null
            source.issuesFromPins = {
                ResolvedObfuscation(MaskResolution.Absent, MaskResolution.Ignored(ObfuscationProblem.LengthFromZero))
            }
            viewModel.onPinRow("fragmentation-packets", "tlshello")
            advanceUntilIdle()
            viewModel.state.value.noiseIgnored shouldBe ObfuscationProblem.LengthFromZero
        }

    @Test
    fun pinShowsTheProviderSuggestionAlongsideThePinnedValue() =
        runTest {
            viewModel.load(SUBSCRIPTION_ID)
            viewModel.onPinRow(KEY_INTERVAL, "24")
            advanceUntilIdle()

            source.pinned shouldBe Triple(SUBSCRIPTION_ID, KEY_INTERVAL, "24")
            viewModel.state.value.rows.first { it.row.key == KEY_INTERVAL }.row shouldBe
                SettingRowState(KEY_INTERVAL, "24", declinedProviderValue = "6", isPinned = true)
        }

    @Test
    fun unpinRestoresTheProviderValue() =
        runTest {
            viewModel.load(SUBSCRIPTION_ID)
            viewModel.onPinRow(KEY_INTERVAL, "24")
            advanceUntilIdle()
            viewModel.onUnpinRow(KEY_INTERVAL)
            advanceUntilIdle()

            source.unpinned shouldBe (SUBSCRIPTION_ID to KEY_INTERVAL)
            viewModel.state.value.rows.first { it.row.key == KEY_INTERVAL }.row shouldBe
                SettingRowState(KEY_INTERVAL, "6", declinedProviderValue = null, isPinned = false)
        }

    @Test
    fun deleteMarksTheScreenAsDeletedOnlyAfterTheSourceDeletesIt() =
        runTest {
            // "Only after" is an ordering claim, and asserting both facts after advanceUntilIdle()
            // could not check it: a ViewModel that set deleted = true *before* awaiting
            // deleteSubscription — the exact bug the name describes — passed identically. The gate
            // holds the source mid-delete so the intermediate state is observable.
            source.deleteGate = CompletableDeferred()
            viewModel.load(SUBSCRIPTION_ID)
            viewModel.onDelete()
            advanceUntilIdle()

            viewModel.state.value.deleted shouldBe false

            source.deleteGate?.complete(Unit)
            advanceUntilIdle()

            source.deletedId shouldBe SUBSCRIPTION_ID
            viewModel.state.value.deleted shouldBe true
        }

    @Test
    fun invalidPinnedValuesAreNotPersisted() =
        runTest {
            viewModel.load(SUBSCRIPTION_ID)
            viewModel.onPinRow(KEY_TIMEOUT, "16")
            advanceUntilIdle()

            source.pinned shouldBe null
        }

    @Test
    fun providerUserAgentRemainsVisibleAlongsideTheUserOverride() =
        runTest {
            viewModel.load(SUBSCRIPTION_ID)
            advanceUntilIdle()

            viewModel.state.value.providerUserAgent shouldBe "Provider Agent"
        }

    @Test
    fun globalHwidGateIsVisibleInTheDetailState() =
        runTest {
            viewModel.load(SUBSCRIPTION_ID)
            source.globalHwidEnabledState.value = false
            advanceUntilIdle()

            viewModel.state.value.globalHwidEnabled shouldBe false
        }

    @Test
    fun aValidUserAgentOverrideReachesTheSource() =
        runTest {
            // The positive case this file was missing entirely. Without it,
            // `invalidUserAgentOverridesAreNotPersisted` below asserts that a field the fixtures
            // never drive non-null is still null — which passes with onUserAgentOverrideChanged
            // implemented as an empty body.
            viewModel.load(SUBSCRIPTION_ID)
            viewModel.onUserAgentOverrideChanged("v2rayNG/1.8.5")
            advanceUntilIdle()

            source.userAgentOverride shouldBe "v2rayNG/1.8.5"
        }

    @Test
    fun invalidUserAgentOverridesAreNotPersisted() =
        runTest {
            // Starts from a *persisted* override, so "not persisted" means "the bad value did not
            // replace the good one" rather than "the field is still at its initial null". The
            // earlier version could not tell those apart.
            viewModel.load(SUBSCRIPTION_ID)
            viewModel.onUserAgentOverrideChanged("v2rayNG/1.8.5")
            advanceUntilIdle()

            viewModel.onUserAgentOverrideChanged("a".repeat(257))
            viewModel.onUserAgentOverrideChanged("Agent\nInjected")
            advanceUntilIdle()

            source.userAgentOverride shouldBe "v2rayNG/1.8.5"
        }

    @Test
    fun refreshNowDoesNotStartASecondSyncWhileTheFirstIsRunning() =
        runTest {
            source.syncGate = CompletableDeferred()
            viewModel.load(SUBSCRIPTION_ID)
            viewModel.onRefreshNow()
            source.syncStarted.await()
            viewModel.onRefreshNow()

            source.syncCalls shouldBe 1
            source.syncGate?.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun theRequestShowsWhileUnaskedWithTargets() =
        runTest {
            source.redirectStatus.value = RedirectStatus(listOf("new.example"), emptyList())
            viewModel.load(SUBSCRIPTION_ID)
            advanceUntilIdle()

            viewModel.state.value.showsRedirectRequest shouldBe true
            viewModel.state.value.redirectTargetHosts shouldBe listOf("new.example")
        }

    @Test
    fun allowAndDenyWriteTheTrust() =
        runTest {
            viewModel.load(SUBSCRIPTION_ID)
            viewModel.onAllowRedirects()
            viewModel.onDenyRedirects()
            viewModel.onRedirectsAllowedChanged(true)
            advanceUntilIdle()

            source.trustWrites shouldBe listOf(RedirectTrust.Allowed, RedirectTrust.Denied, RedirectTrust.Allowed)
        }

    @Test
    fun aValidUnaskedProviderResolverShowsTheRequest() =
        runTest {
            source.resolverStatus.value = ResolverStatus(VALID_RESOLVER, ResolverTrust.Unasked)
            viewModel.load(SUBSCRIPTION_ID)
            advanceUntilIdle()

            viewModel.state.value.showsResolverRequest shouldBe true
            viewModel.state.value.resolverHost shouldBe "dns.example"
        }

    @Test
    fun allowWritesTrustBoundToTheHost() =
        runTest {
            source.resolverStatus.value = ResolverStatus(VALID_RESOLVER, ResolverTrust.Unasked)
            viewModel.load(SUBSCRIPTION_ID)
            advanceUntilIdle()

            viewModel.onAllowResolver()
            advanceUntilIdle()

            source.lastResolverTrust shouldBe Triple(SUBSCRIPTION_ID, ResolverTrust.Allowed, "dns.example")
        }

    @Test
    fun denyAndTheSwitchWriteTheShownHost() =
        runTest {
            source.resolverStatus.value = ResolverStatus(VALID_RESOLVER, ResolverTrust.Allowed)
            viewModel.load(SUBSCRIPTION_ID)
            advanceUntilIdle()

            viewModel.state.value.showsResolverSwitch shouldBe true
            viewModel.onResolverAllowedChanged(false)
            advanceUntilIdle()
            source.lastResolverTrust shouldBe Triple(SUBSCRIPTION_ID, ResolverTrust.Denied, "dns.example")

            viewModel.onDenyResolver()
            advanceUntilIdle()
            source.lastResolverTrust shouldBe Triple(SUBSCRIPTION_ID, ResolverTrust.Denied, "dns.example")
        }

    @Test
    fun invalidProviderShowsNoRequest() =
        runTest {
            source.resolverStatus.value =
                ResolverStatus(ProviderResolver.Invalid("server-address-resolve-dns-domain"), ResolverTrust.Unasked)
            viewModel.load(SUBSCRIPTION_ID)
            advanceUntilIdle()

            viewModel.state.value.showsResolverRequest shouldBe false
            viewModel.onAllowResolver()
            advanceUntilIdle()
            source.lastResolverTrust shouldBe null
        }

    @Test
    fun anUnaskedRouteExclusionShowsTheRequestAndAnswersBindToItsFingerprint() =
        runTest {
            val lan = CidrRange("172.16.0.0", 12, isV6 = false)
            val wide = CidrRange("0.0.0.0", 0, isV6 = false)
            source.routeStatus.value = RouteExclusionStatus(RouteExclusionTrust.Unasked, "fp", listOf(wide, lan))
            viewModel.load(SUBSCRIPTION_ID)
            advanceUntilIdle()

            viewModel.state.value.routeExclusionRequest shouldBe
                RouteExclusionRequest("fp", applied = listOf("172.16.0.0/12"), refused = listOf("0.0.0.0/0"))
            viewModel.onDenyRouteExclusion()
            advanceUntilIdle()
            source.lastRouteTrust shouldBe Triple(SUBSCRIPTION_ID, RouteExclusionTrust.Denied, "fp")
            viewModel.onAllowRouteExclusion()
            advanceUntilIdle()
            source.lastRouteTrust shouldBe Triple(SUBSCRIPTION_ID, RouteExclusionTrust.Allowed, "fp")
        }

    @Test
    fun anAnsweredRouteExclusionShowsNothingAndWritesNothing() =
        runTest {
            source.routeStatus.value =
                RouteExclusionStatus(RouteExclusionTrust.Allowed, "fp", listOf(CidrRange("172.16.0.0", 12, false)))
            viewModel.load(SUBSCRIPTION_ID)
            advanceUntilIdle()

            viewModel.state.value.routeExclusionRequest shouldBe null
            viewModel.onAllowRouteExclusion()
            advanceUntilIdle()
            source.lastRouteTrust shouldBe null
        }

    @Test
    fun theUserSwitchIsFoldedIntoState() =
        runTest {
            source.preResolveOn.value = true
            viewModel.load(SUBSCRIPTION_ID)
            advanceUntilIdle()

            viewModel.state.value.userPreResolveOn shouldBe true
        }

    @Test
    fun stateToStringNamesNoResolverHost() =
        runTest {
            source.resolverStatus.value = ResolverStatus(VALID_RESOLVER, ResolverTrust.Allowed)
            viewModel.load(SUBSCRIPTION_ID)
            advanceUntilIdle()

            viewModel.state.value.toString().contains("dns.example") shouldBe false
        }

    @Test
    fun anInvalidLinkFragmentShowsItsProblem() =
        runTest {
            source.subscriptions.value =
                listOf(subscription(SUBSCRIPTION_ID).copy(linkFragment = "0-5,10,tlshello"))
            viewModel.load(SUBSCRIPTION_ID)
            advanceUntilIdle()

            viewModel.state.value.linkFragmentProblem shouldBe ObfuscationProblem.LengthFromZero
        }

    @Test
    fun savingFrontingAndFragmentPassesThroughBlankAsNull() =
        runTest {
            viewModel.load(SUBSCRIPTION_ID)
            viewModel.onFrontingSaved("front.example", " ")
            viewModel.onLinkFragmentSaved("")
            advanceUntilIdle()

            source.frontingWrites shouldBe listOf("front.example" to null)
            source.fragmentWrites shouldBe listOf(null)
        }

    private companion object {
        const val SUBSCRIPTION_ID = 7L
        val VALID_RESOLVER = ProviderResolver.Valid("https://dns.example/q", "dns.example", null)
        const val GROUP_ID = 9L
        const val KEY_INTERVAL = "profile-update-interval"
        const val KEY_TIMEOUT = "subscription-request-timeout"
        const val KEY_AUTO_UPDATE = "subscription-auto-update-enable"
        const val KEY_AUTO_UPDATE_OPEN = "subscription-auto-update-open-enable"
        const val KEY_USER_AGENT = "change-user-agent"

        fun subscription(id: Long) =
            StoredSubscription(
                id = id,
                groupId = GROUP_ID,
                url = "https://panel.example/sub/token",
                userAgentOverride = null,
                hwidEnabled = true,
                lastFetchedAt = null,
                lastFetchStatus = null,
                lastFetchDetail = null,
            )
    }
}
