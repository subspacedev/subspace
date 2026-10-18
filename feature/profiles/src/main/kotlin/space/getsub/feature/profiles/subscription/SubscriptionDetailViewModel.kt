// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import space.getsub.core.data.MaskResolution
import space.getsub.core.data.OBFUSCATION_DIRECTIVE_KEYS
import space.getsub.core.data.ProfileGroup
import space.getsub.core.data.RedirectStatus
import space.getsub.core.data.RedirectTrust
import space.getsub.core.data.ResolvedObfuscation
import space.getsub.core.data.ResolverTrust
import space.getsub.core.data.RouteExclusionTrust
import space.getsub.core.data.StoredSubscription
import space.getsub.core.data.preresolve.ResolverStatus
import space.getsub.core.model.Checked
import space.getsub.core.parser.LinkFragment
import space.getsub.core.parser.directive.DirectiveRegistry
import space.getsub.core.parser.directive.KindResult
import space.getsub.core.parser.directive.UserInfo
import space.getsub.core.parser.directive.canonicalise
import space.getsub.core.parser.directive.parseUserInfo
import space.getsub.feature.profiles.ProfileSource
import space.getsub.feature.profiles.R
import space.getsub.feature.profiles.RouteExclusionRequest
import space.getsub.feature.profiles.add.UserMessage
import space.getsub.feature.profiles.add.toUserMessage
import space.getsub.feature.profiles.request
import javax.inject.Inject

private const val KEY_INTERVAL = "profile-update-interval"
private const val KEY_TIMEOUT = "subscription-request-timeout"
private const val KEY_AUTO_UPDATE = "subscription-auto-update-enable"
private const val KEY_AUTO_UPDATE_OPEN = "subscription-auto-update-open-enable"
private const val KEY_USER_AGENT = "change-user-agent"

// Mirrors RefreshScheduler.DEFAULT_INTERVAL_HOURS and SubscriptionSyncer.DEFAULT_TIMEOUT_SECONDS
// exactly — duplicated, not shared, for the same reason RefreshScheduler's own copy of
// DirectiveKind.Integer(1, 8760)'s bounds is duplicated rather than imported: `:feature:profiles`
// has no path to either the `:app`-only or :core:data-internal constant for one number, and this
// row must show what the app will actually fall back to, not an invented placeholder.
private const val DEFAULT_INTERVAL_HOURS = "12"
private const val DEFAULT_TIMEOUT_SECONDS = "9"
private const val DEFAULT_AUTO_UPDATE = "true"

private val NO_DIRECTIVE_ISSUES = ResolvedObfuscation(MaskResolution.Absent, MaskResolution.Absent)

/**
 * The subscription's obfuscation directive issues, re-read whenever any obfuscation directive's
 * effective value changes — a sync, a pin or an unpin — and on every [refreshes] emission, so the
 * "directive ignored" lines never go stale (M9-A spec §2.4).
 */
private fun ProfileSource.observeDirectiveIssues(
    id: Long,
    refreshes: Flow<Int>,
): Flow<ResolvedObfuscation> {
    val directives =
        combine(OBFUSCATION_DIRECTIVE_KEYS.map { observeEffective(id, it, null) }) { it.toList() }
            .distinctUntilChanged()
    return combine(directives, refreshes) { _, _ ->
        // A failed read shows no "ignored" line; it must never end the combine that drives the screen.
        runCatching { obfuscationDirectiveIssues(id) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(NO_DIRECTIVE_ISSUES)
    }
}

private fun canonicalUserAgent(raw: String): String? {
    val canonical =
        DirectiveRegistry.specs[KEY_USER_AGENT]?.kind?.canonicalise(raw)
            as? KindResult.Canonical
            ?: return null
    return canonical.value.takeIf { value -> value.none(Char::isISOControl) }
}

/**
 * `:app`'s `SubscriptionDetail(subscriptionId)` route.
 *
 * Follows [space.getsub.feature.profiles.editor.EditorViewModel]'s shape: [load] is
 * called once per navigation (from the screen's `LaunchedEffect(subscriptionId)`), not injected
 * via a constructor argument, so this class stays a plain `hiltViewModel()` with no
 * `SavedStateHandle` wiring.
 */
// One small action per setting the screen edits; splitting them off would scatter one screen's contract.
@Suppress("TooManyFunctions")
@HiltViewModel
internal class SubscriptionDetailViewModel
@Inject
constructor(
    private val profileSource: ProfileSource,
) : ViewModel() {
    private val _state = MutableStateFlow(SubscriptionDetailState())
    val state: StateFlow<SubscriptionDetailState> = _state.asStateFlow()

    /**
     * Refresh-in-flight/result/delete signals — not derived from [ProfileSource]'s own flows, so
     * kept apart from [buildState]'s inputs and merged back in on every recomposition of them.
     */
    private val ephemeral = MutableStateFlow(Ephemeral())

    /**
     * Bumped when a refresh completes. The obfuscation directives' own flows already re-read the
     * issues whenever a sync writes them; this is the belt to that, for a sync whose write the
     * flows do not see (another process's Room instance).
     */
    private val issuesTick = MutableStateFlow(0)

    private var loadedId: Long? = null
    private var loadJob: Job? = null
    private var refreshJob: Job? = null

    fun load(subscriptionId: Long) {
        if (loadedId == subscriptionId) return
        loadedId = subscriptionId
        loadJob?.cancel()
        _state.value = SubscriptionDetailState(subscriptionId = subscriptionId)
        ephemeral.value = Ephemeral()
        issuesTick.value = 0

        val subscriptionFlow =
            profileSource.observeSubscriptions().map { subs ->
                subs.firstOrNull { it.id == subscriptionId }
            }
        val groupFlow = profileSource.observeGroups(query = "", protocol = null)
        val userInfoFlow =
            profileSource.observeUserInfo(subscriptionId).map { raw -> raw?.let(::parseUserInfo) }
        val rowsFlow = observeDirectiveRows(subscriptionId)
        val reachFlow = observeReach(subscriptionId)
        val detailsFlow =
            combine(
                rowsFlow,
                profileSource.observeEffective(subscriptionId, KEY_USER_AGENT, default = null),
                profileSource.globalHwidEnabled,
                profileSource.observeDirectiveIssues(subscriptionId, refreshes = issuesTick),
                reachFlow,
            ) { rows, userAgent, globalHwidEnabled, issues, reach ->
                DetailSettings(
                    rows = rows,
                    providerUserAgent = userAgent.providerValue,
                    globalHwidEnabled = globalHwidEnabled,
                    issues = issues,
                    reach = reach,
                )
            }

        loadJob =
            combine(subscriptionFlow, groupFlow, userInfoFlow, detailsFlow, ephemeral) {
                    subscription,
                    groups,
                    userInfo,
                    settings,
                    eph,
                ->
                buildState(
                    DetailInputs(
                        subscriptionId = subscriptionId,
                        subscription = subscription,
                        groups = groups,
                        userInfo = userInfo,
                        rows = settings.rows,
                        providerUserAgent = settings.providerUserAgent,
                        globalHwidEnabled = settings.globalHwidEnabled,
                        issues = settings.issues,
                        reach = settings.reach,
                        ephemeral = eph,
                    ),
                )
            }.onEach { _state.value = it }.launchIn(viewModelScope)
    }

    private fun observeReach(id: Long): Flow<Reach> =
        combine(
            profileSource.observeRedirectStatus(id),
            profileSource.globalFragmentationOn,
            profileSource.observeResolverStatus(id),
            profileSource.userPreResolveOn,
            profileSource.observeRouteExclusionStatus(id),
        ) { redirect, fragmentationOn, resolver, userPreResolveOn, routes ->
            Reach(redirect, fragmentationOn, resolver, userPreResolveOn, routes.request())
        }

    private fun observeDirectiveRows(id: Long): Flow<List<DirectiveRow>> =
        combine(
            profileSource.observeEffective(id, KEY_INTERVAL, DEFAULT_INTERVAL_HOURS),
            profileSource.observeEffective(id, KEY_TIMEOUT, DEFAULT_TIMEOUT_SECONDS),
            profileSource.observeEffective(id, KEY_AUTO_UPDATE, DEFAULT_AUTO_UPDATE),
            profileSource.observeEffective(id, KEY_AUTO_UPDATE_OPEN, DEFAULT_AUTO_UPDATE),
        ) { interval, timeout, autoUpdate, autoUpdateOpen ->
            listOf(
                DirectiveRow(
                    DirectiveRowKind.Hours,
                    R.string.subscription_row_interval_label,
                    SettingRowState.from(interval),
                ),
                DirectiveRow(
                    DirectiveRowKind.Seconds,
                    R.string.subscription_row_timeout_label,
                    SettingRowState.from(timeout),
                ),
                DirectiveRow(
                    DirectiveRowKind.Toggle,
                    R.string.subscription_row_auto_update_label,
                    SettingRowState.from(autoUpdate),
                ),
                DirectiveRow(
                    DirectiveRowKind.Toggle,
                    R.string.subscription_row_auto_update_open_label,
                    SettingRowState.from(autoUpdateOpen),
                ),
            )
        }

    private fun buildState(inputs: DetailInputs): SubscriptionDetailState {
        val subscription = inputs.subscription
        if (subscription == null) {
            return SubscriptionDetailState(
                subscriptionId = inputs.subscriptionId,
                loading = false,
                notFound = true,
                deleted = inputs.ephemeral.deleted,
            )
        }
        val group = inputs.groups.firstOrNull { it.id == subscription.groupId }
        return SubscriptionDetailState(
            subscriptionId = inputs.subscriptionId,
            loading = false,
            notFound = false,
            groupName = group?.name.orEmpty(),
            profileCount = group?.profiles?.size ?: 0,
            redactedUrl = redactUrl(subscription.url),
            lastFetchState = subscription.toLastFetchState(),
            // UserInfo.usedBytes defaults an absent upload/download to zero (its own KDoc);
            // correct only when the provider sent at least one of the two — see QuotaBar's own
            // "draw nothing you cannot feed" rule, which ServersViewModel.buildState already
            // applies for the identical reason.
            quotaUsedBytes = inputs.userInfo?.takeIf { it.upload != null || it.download != null }?.usedBytes,
            quotaTotalBytes = inputs.userInfo?.total,
            hwidEnabled = subscription.hwidEnabled,
            globalHwidEnabled = inputs.globalHwidEnabled,
            userAgentOverride = subscription.userAgentOverride,
            providerUserAgent = inputs.providerUserAgent,
            rows = inputs.rows,
            isRefreshing = inputs.ephemeral.isRefreshing,
            refreshResult = inputs.ephemeral.refreshResult,
            deleted = inputs.ephemeral.deleted,
            fragmentIgnored = (inputs.issues.fragment as? MaskResolution.Ignored)?.problem,
            noiseIgnored = (inputs.issues.noise as? MaskResolution.Ignored)?.problem,
            redirectTrust = subscription.redirectTrust,
            redirectTargetHosts = inputs.reach.redirect.targetHosts,
            redirectIssues = inputs.reach.redirect.issues,
            resolveAddress = subscription.resolveAddress,
            hostHeader = subscription.hostHeader,
            linkFragment = subscription.linkFragment,
            linkFragmentProblem =
            subscription.linkFragment?.let { (LinkFragment.parse(it) as? Checked.Invalid)?.problem },
            globalFragmentationOn = inputs.reach.fragmentationOn,
            movedFromHost = subscription.movedFromHost,
            movedAt = subscription.movedAt,
            lastFetchVia = subscription.lastFetchVia,
            insecureIgnored = subscription.insecureIgnored,
            resolverProvider = inputs.reach.resolver.provider,
            resolverTrust = inputs.reach.resolver.trust,
            userPreResolveOn = inputs.reach.userPreResolveOn,
            routeExclusionRequest = inputs.reach.routeExclusionRequest,
        )
    }

    private data class DetailInputs(
        val subscriptionId: Long,
        val subscription: StoredSubscription?,
        val groups: List<ProfileGroup>,
        val userInfo: UserInfo?,
        val rows: List<DirectiveRow>,
        val providerUserAgent: String?,
        val globalHwidEnabled: Boolean,
        val issues: ResolvedObfuscation,
        val reach: Reach,
        val ephemeral: Ephemeral,
    )

    private data class DetailSettings(
        val rows: List<DirectiveRow>,
        val providerUserAgent: String?,
        val globalHwidEnabled: Boolean,
        val issues: ResolvedObfuscation,
        val reach: Reach,
    )

    /** Everything "Reaching the subscription" shows besides the stored row (M9-B §4, M9-C §4.2, M9-E §4). */
    private data class Reach(
        val redirect: RedirectStatus,
        val fragmentationOn: Boolean,
        val resolver: ResolverStatus,
        val userPreResolveOn: Boolean,
        val routeExclusionRequest: RouteExclusionRequest?,
    )

    /**
     * [space.getsub.feature.profiles.list.ServersGroupList]'s Update button, mirrored
     * here for this one subscription.
     */
    fun onRefreshNow() {
        val id = loadedId ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            ephemeral.value = ephemeral.value.copy(isRefreshing = true, refreshResult = null)
            val result = profileSource.syncSubscription(id)
            issuesTick.value++
            ephemeral.value = ephemeral.value.copy(isRefreshing = false, refreshResult = result.toUserMessage())
        }
    }

    fun onDismissRefreshResult() {
        ephemeral.value = ephemeral.value.copy(refreshResult = null)
    }

    /** Pins [value] for [key] — spec D3: the user's pin, once set, survives every subsequent provider update. */
    fun onPinRow(
        key: String,
        value: String,
    ) {
        val id = loadedId ?: return
        val canonical =
            value
                .trim()
                .takeIf(String::isNotEmpty)
                ?.let { DirectiveRegistry.specs[key]?.kind?.canonicalise(it) }
                as? KindResult.Canonical
                ?: return
        viewModelScope.launch { profileSource.pin(id, key, canonical.value) }
    }

    /** Hands [key] back to the provider. */
    fun onUnpinRow(key: String) {
        val id = loadedId ?: return
        viewModelScope.launch { profileSource.unpin(id, key) }
    }

    fun onHwidEnabledChanged(enabled: Boolean) {
        val id = loadedId ?: return
        viewModelScope.launch { profileSource.setHwidEnabled(id, enabled) }
    }

    /** [userAgent] blank or `null` clears the override — see [ProfileSource.setUserAgentOverride]. */
    fun onUserAgentOverrideChanged(userAgent: String?) {
        val id = loadedId ?: return
        val canonical =
            if (userAgent.isNullOrBlank()) {
                null
            } else {
                canonicalUserAgent(userAgent) ?: return
            }
        viewModelScope.launch { profileSource.setUserAgentOverride(id, canonical) }
    }

    fun onAllowRedirects() = setTrust(RedirectTrust.Allowed)

    fun onDenyRedirects() = setTrust(RedirectTrust.Denied)

    fun onRedirectsAllowedChanged(allowed: Boolean) =
        setTrust(if (allowed) RedirectTrust.Allowed else RedirectTrust.Denied)

    private fun setTrust(trust: RedirectTrust) {
        val id = loadedId ?: return
        viewModelScope.launch { profileSource.setRedirectTrust(id, trust) }
    }

    fun onAllowResolver() = setResolverTrust(ResolverTrust.Allowed)

    fun onDenyResolver() = setResolverTrust(ResolverTrust.Denied)

    fun onResolverAllowedChanged(allowed: Boolean) =
        setResolverTrust(if (allowed) ResolverTrust.Allowed else ResolverTrust.Denied)

    /** M9-C spec §1.3: the answer is bound to the DoH host the user was shown, not one read later. */
    private fun setResolverTrust(trust: ResolverTrust) {
        val id = loadedId ?: return
        val host = _state.value.resolverHost ?: return
        viewModelScope.launch { profileSource.setResolverTrust(id, trust, host) }
    }

    fun onAllowRouteExclusion(fingerprint: String) = setRouteExclusionTrust(RouteExclusionTrust.Allowed, fingerprint)

    fun onDenyRouteExclusion(fingerprint: String) = setRouteExclusionTrust(RouteExclusionTrust.Denied, fingerprint)

    /**
     * M9-E spec §4: the answer is bound to the list the user was shown. [fingerprint] comes from the
     * rendered card, not from state read at click time, which a newer list may already have replaced.
     */
    private fun setRouteExclusionTrust(
        trust: RouteExclusionTrust,
        fingerprint: String,
    ) {
        val id = loadedId ?: return
        viewModelScope.launch { profileSource.setRouteExclusionTrust(id, trust, fingerprint) }
    }

    /** Blank values clear the field — see [ProfileSource.setFronting]. */
    fun onFrontingSaved(
        resolveAddress: String?,
        hostHeader: String?,
    ) {
        val id = loadedId ?: return
        val address = resolveAddress?.trim()?.ifEmpty { null }
        val host = hostHeader?.trim()?.ifEmpty { null }
        viewModelScope.launch { profileSource.setFronting(id, address, host) }
    }

    /** A blank [value] clears the link's own fetch fragmentation — see [ProfileSource.setLinkFragment]. */
    fun onLinkFragmentSaved(value: String?) {
        val id = loadedId ?: return
        viewModelScope.launch { profileSource.setLinkFragment(id, value?.trim()?.ifEmpty { null }) }
    }

    /**
     * Deletes this subscription — its group, its servers, its directives and its overrides
     * (spec §A.1: deletion must cascade). The caller confirms first; see [SubscriptionDetailState.deleted]'s
     * own KDoc for how the screen tells "the user just deleted this" from "this id was never
     * valid" apart.
     */
    fun onDelete() {
        val id = loadedId ?: return
        viewModelScope.launch {
            profileSource.deleteSubscription(id)
            ephemeral.value = ephemeral.value.copy(deleted = true)
        }
    }

    private data class Ephemeral(
        val isRefreshing: Boolean = false,
        val refreshResult: UserMessage? = null,
        val deleted: Boolean = false,
    )
}
