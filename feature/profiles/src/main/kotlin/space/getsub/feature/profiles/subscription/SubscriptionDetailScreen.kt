// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the paddings/gaps below — all are
// tokens/spacing.css's --space-* scale, already named by the val each
// initializes. See core/ui's GroupCard.kt for the same pattern.
@file:Suppress(
    "MagicNumber",
    // Stateless Compose helpers keep each detail row focused and directly testable. Splitting
    // them into artificial files would hide their shared SubscriptionDetailContent contract.
    "TooManyFunctions",
)

package space.getsub.feature.profiles.subscription

import android.content.ClipData
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import space.getsub.core.data.FetchVia
import space.getsub.core.data.RedirectIssue
import space.getsub.core.data.RedirectTrust
import space.getsub.core.data.ResolverTrust
import space.getsub.core.data.TuningDirectiveIssues
import space.getsub.core.data.isDirectiveEnabled
import space.getsub.core.data.preresolve.ProviderResolver
import space.getsub.core.model.identity.DeviceHeader
import space.getsub.core.parser.RedirectRejection
import space.getsub.core.ui.TuningIgnoreKind
import space.getsub.core.ui.component.BannerTone
import space.getsub.core.ui.component.HubRow
import space.getsub.core.ui.component.QuotaBar
import space.getsub.core.ui.component.SectionTitle
import space.getsub.core.ui.component.SubspaceDialog
import space.getsub.core.ui.component.SwitchRow
import space.getsub.core.ui.component.TonalBanner
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.textRes
import space.getsub.core.ui.theme.SubspaceShapeTokens
import space.getsub.core.ui.theme.mono
import space.getsub.core.ui.tuningDirectiveLine
import space.getsub.core.ui.tuningIgnoreKind
import space.getsub.feature.profiles.R
import space.getsub.feature.profiles.RouteExclusionRequestContent
import space.getsub.feature.profiles.add.UserMessage
import space.getsub.feature.profiles.add.toUserMessage
import space.getsub.feature.profiles.add.userMessageText

private val CONTENT_PADDING = 16.dp
private val FIELD_GAP = 12.dp
private val ROW_GAP = 4.dp
private val HWID_INDENT = 40.dp

/** Identifiers for instrumented tests of the "Sent to the provider" section. */
internal const val SUBSCRIPTION_HWID_FIELD_TEST_TAG = "subscription-hwid-field"
internal const val SUBSCRIPTION_HWID_VALUE_TEST_TAG = "subscription-hwid-value"
internal const val SUBSCRIPTION_HEADER_FIELD_TEST_TAG = "subscription-header-field"

internal fun headerTestTag(header: DeviceHeader): String = "subscription-header-${header.wireName}"

/**
 * Identifies the loading spinner to instrumented tests — same pattern as
 * [EditorScreen][space.getsub.feature.profiles.editor.EditorScreen]'s own tag.
 */
internal const val SUBSCRIPTION_DETAIL_LOADING_TEST_TAG = "subscription-detail-loading"

/**
 * `:app`'s `SubscriptionDetail(subscriptionId)` route — M3 deliberately left this route
 * undeclared awaiting M4 data (its plan Part 2 line 438); this fills it in.
 *
 * Makes spec D3's precedence visible: a pinned field shows both its own value and the provider
 * value it is declining, never just one ("provider suggests 6 h · you pinned 24 h").
 *
 * [onDone] fires for the back button, the not-found screen's own back button, and once a
 * delete this screen itself started actually completes ([SubscriptionDetailState.deleted]) — the
 * caller (`SubspaceNavHost`) pops the back stack the same way regardless of which of those three
 * fired, exactly like [EditorScreen][space.getsub.feature.profiles.editor.EditorScreen]'s
 * own `onDone`.
 */
@Composable
fun SubscriptionDetailScreen(
    subscriptionId: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SubscriptionDetailViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(subscriptionId) { viewModel.load(subscriptionId) }
    LaunchedEffect(state.deleted) { if (state.deleted) onDone() }

    SubscriptionDetailContent(
        state = state,
        actions =
        SubscriptionDetailActions(
            onBack = onDone,
            onRefreshNow = viewModel::onRefreshNow,
            onDismissRefreshResult = viewModel::onDismissRefreshResult,
            onPinRow = viewModel::onPinRow,
            onUnpinRow = viewModel::onUnpinRow,
            onHwidEnabledChanged = viewModel::onHwidEnabledChanged,
            onHwidDraftChanged = viewModel::onHwidDraftChanged,
            onHwidSave = viewModel::onHwidSave,
            onHwidRandom = viewModel::onHwidRandom,
            onHeaderEnabledChanged = viewModel::onHeaderEnabledChanged,
            onHeaderLiteralSave = viewModel::onHeaderLiteralSave,
            onHeaderReset = viewModel::onHeaderReset,
            onUserAgentOverrideChanged = viewModel::onUserAgentOverrideChanged,
            onDeleteConfirmed = viewModel::onDelete,
            onAllowRedirects = viewModel::onAllowRedirects,
            onDenyRedirects = viewModel::onDenyRedirects,
            onRedirectsAllowedChanged = viewModel::onRedirectsAllowedChanged,
            onFrontingSaved = viewModel::onFrontingSaved,
            onLinkFragmentSaved = viewModel::onLinkFragmentSaved,
            onAllowResolver = viewModel::onAllowResolver,
            onDenyResolver = viewModel::onDenyResolver,
            onResolverAllowedChanged = viewModel::onResolverAllowedChanged,
            onAllowRouteExclusion = viewModel::onAllowRouteExclusion,
            onDenyRouteExclusion = viewModel::onDenyRouteExclusion,
        ),
        modifier = modifier,
    )
}

/**
 * [SubscriptionDetailContent]'s callbacks, grouped for the same reason
 * [space.getsub.feature.profiles.editor.EditorActions] is.
 */
internal data class SubscriptionDetailActions(
    val onBack: () -> Unit,
    val onRefreshNow: () -> Unit,
    val onDismissRefreshResult: () -> Unit,
    val onPinRow: (key: String, value: String) -> Unit,
    val onUnpinRow: (key: String) -> Unit,
    val onHwidEnabledChanged: (Boolean) -> Unit,
    val onUserAgentOverrideChanged: (String?) -> Unit,
    val onDeleteConfirmed: () -> Unit,
    val onAllowRedirects: () -> Unit,
    val onDenyRedirects: () -> Unit,
    val onRedirectsAllowedChanged: (Boolean) -> Unit,
    val onFrontingSaved: (resolveAddress: String?, hostHeader: String?) -> Unit,
    val onLinkFragmentSaved: (String?) -> Unit,
    // M10-B spec §3.5. Defaulted so callers that do not show the section need not wire it.
    val onHwidDraftChanged: (String) -> Unit = {},
    val onHwidSave: () -> Boolean = { true },
    val onHwidRandom: () -> Unit = {},
    val onHeaderEnabledChanged: (DeviceHeader, Boolean) -> Unit = { _, _ -> },
    val onHeaderLiteralSave: (DeviceHeader, String) -> Boolean = { _, _ -> true },
    val onHeaderReset: (DeviceHeader) -> Unit = {},
    // M9-C spec §4.2. Defaulted so callers that do not show the resolver need not wire it.
    val onAllowResolver: () -> Unit = {},
    val onDenyResolver: () -> Unit = {},
    val onResolverAllowedChanged: (Boolean) -> Unit = {},
    // M9-E spec §4, with the fingerprint of the rendered request. Defaulted for the same reason.
    val onAllowRouteExclusion: (fingerprint: String) -> Unit = {},
    val onDenyRouteExclusion: (fingerprint: String) -> Unit = {},
)

/**
 * The stateless half — see [space.getsub.feature.home.HomeScreenContent]'s KDoc for why
 * this split exists. Exercised directly by `SubscriptionDetailScreenTest`, no Hilt or
 * [SubscriptionDetailViewModel] required.
 */
@Composable
internal fun SubscriptionDetailContent(
    state: SubscriptionDetailState,
    actions: SubscriptionDetailActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SubscriptionDetailTopBar(title = state.groupName, onBack = actions.onBack)
        when {
            state.loading -> LoadingBody()
            state.notFound -> NotFoundBody(onBack = actions.onBack)
            else -> DetailBody(state = state, actions = actions)
        }
    }
}

@Composable
private fun SubscriptionDetailTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(CONTENT_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FIELD_GAP),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(SubspaceIcons.ArrowBack),
                contentDescription = stringResource(R.string.subscription_detail_back_description),
            )
        }
        Text(text = title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun LoadingBody(modifier: Modifier = Modifier) {
    val loadingDescription = stringResource(R.string.subscription_detail_loading_description)
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier =
            Modifier
                .testTag(SUBSCRIPTION_DETAIL_LOADING_TEST_TAG)
                .semantics { contentDescription = loadingDescription },
        )
    }
}

@Composable
private fun NotFoundBody(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(CONTENT_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FIELD_GAP, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.subscription_detail_not_found_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.subscription_detail_not_found_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onBack) { Text(stringResource(R.string.subscription_detail_not_found_back_button)) }
    }
}

@Composable
private fun DetailBody(
    state: SubscriptionDetailState,
    actions: SubscriptionDetailActions,
    modifier: Modifier = Modifier,
) {
    var deleteRequested by remember { mutableStateOf(false) }

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CONTENT_PADDING)
            .padding(bottom = CONTENT_PADDING),
    ) {
        TrustRequests(state = state, actions = actions)
        SectionCard(title = stringResource(R.string.subscription_detail_section_subscription)) {
            UrlRow(redactedUrl = state.redactedUrl)
            LastFetchRow(state = state, onRefreshNow = actions.onRefreshNow)
            state.refreshResult?.let { RefreshResultBanner(message = it, onDismiss = actions.onDismissRefreshResult) }
            if (state.quotaUsedBytes != null && state.quotaTotalBytes != null) {
                QuotaBar(
                    usedBytes = state.quotaUsedBytes,
                    totalBytes = state.quotaTotalBytes,
                    modifier = Modifier.padding(vertical = FIELD_GAP),
                )
            }
        }
        SentToProviderSection(state = state, actions = actions)
        ReachSection(state = state, actions = actions)
        if (state.rows.isNotEmpty()) {
            DirectivesSection(state = state, actions = actions)
        }

        DeleteButton(onClick = { deleteRequested = true }, modifier = Modifier.padding(top = CONTENT_PADDING))
    }

    if (deleteRequested) {
        DeleteSubscriptionDialog(
            name = state.groupName,
            profileCount = state.profileCount,
            onConfirm = {
                deleteRequested = false
                actions.onDeleteConfirmed()
            },
            onDismiss = { deleteRequested = false },
        )
    }
}

/**
 * The provider's pending requests: redirects first (M9-B spec §4), then its DoH resolver (M9-C spec
 * §1.3), then its excluded routes (M9-E spec §4).
 */
@Composable
private fun TrustRequests(
    state: SubscriptionDetailState,
    actions: SubscriptionDetailActions,
) {
    if (state.showsRedirectRequest) {
        TrustRequestCard(
            text = stringResource(R.string.subscription_redirect_request, state.redirectTargetHosts.joinToString(", ")),
            onAllow = actions.onAllowRedirects,
            onDeny = actions.onDenyRedirects,
        )
    }
    val resolverHost = state.resolverHost
    if (state.showsResolverRequest && resolverHost != null) {
        TrustRequestCard(
            text = stringResource(R.string.subscription_resolver_request, resolverHost),
            onAllow = actions.onAllowResolver,
            onDeny = actions.onDenyResolver,
        )
    }
    val routes = state.routeExclusionRequest
    if (routes != null) {
        Surface(
            shape = SubspaceShapeTokens.card,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth().padding(top = CONTENT_PADDING),
        ) {
            RouteExclusionRequestContent(
                request = routes,
                onAllow = { actions.onAllowRouteExclusion(routes.fingerprint) },
                onDeny = { actions.onDenyRouteExclusion(routes.fingerprint) },
                modifier = Modifier.padding(CONTENT_PADDING),
            )
        }
    }
}

/** A provider's request for trust: the text, then Allow and Don't allow. */
@Composable
private fun TrustRequestCard(
    text: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Surface(
        shape = SubspaceShapeTokens.card,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = CONTENT_PADDING),
    ) {
        Column(modifier = Modifier.padding(CONTENT_PADDING), verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                Button(onClick = onAllow) { Text(stringResource(R.string.subscription_redirect_allow)) }
                OutlinedButton(onClick = onDeny) { Text(stringResource(R.string.subscription_redirect_deny)) }
            }
        }
    }
}

@Composable
private fun ReachSection(
    state: SubscriptionDetailState,
    actions: SubscriptionDetailActions,
) {
    SectionCard(title = stringResource(R.string.subscription_detail_section_reach)) {
        if (state.redirectTrust != RedirectTrust.Unasked) {
            SwitchRow(
                icon = SubspaceIcons.VpnLock,
                label = stringResource(R.string.subscription_redirect_switch),
                supporting = null,
                checked = state.redirectTrust == RedirectTrust.Allowed,
                onCheckedChange = actions.onRedirectsAllowedChanged,
            )
        }
        val resolverHost = state.resolverHost
        if (state.showsResolverSwitch && resolverHost != null) {
            SwitchRow(
                icon = SubspaceIcons.VpnLock,
                label = stringResource(R.string.subscription_resolver_switch, resolverHost),
                supporting = null,
                checked = state.resolverTrust == ResolverTrust.Allowed,
                onCheckedChange = actions.onResolverAllowedChanged,
            )
        }
        FrontingFields(state.resolveAddress, state.hostHeader, onSave = actions.onFrontingSaved)
        LinkFragmentField(
            value = state.linkFragment,
            globalOn = state.globalFragmentationOn,
            onSave = actions.onLinkFragmentSaved,
        )
        ReachLines(state)
    }
}

// Machine strings: displayed, never logged (ARCHITECTURE.md §5.6).
@Composable
private fun FrontingFields(
    resolveAddress: String?,
    hostHeader: String?,
    onSave: (String?, String?) -> Unit,
) {
    var address by remember(resolveAddress) { mutableStateOf(resolveAddress.orEmpty()) }
    var host by remember(hostHeader) { mutableStateOf(hostHeader.orEmpty()) }
    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text(stringResource(R.string.subscription_fronting_address_label)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.mono(),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text(stringResource(R.string.subscription_fronting_host_label)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.mono(),
        shape = MaterialTheme.shapes.large,
        trailingIcon = {
            IconButton(onClick = { onSave(address.ifBlank { null }, host.ifBlank { null }) }) {
                Icon(
                    painter = painterResource(SubspaceIcons.Check),
                    contentDescription = stringResource(R.string.subscription_fronting_save),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LinkFragmentField(
    value: String?,
    globalOn: Boolean,
    onSave: (String?) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value.orEmpty()) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(stringResource(R.string.subscription_fetch_fragment_label)) },
        supportingText = if (globalOn) {
            { Text(stringResource(R.string.subscription_fetch_fragment_global)) }
        } else {
            null
        },
        placeholder = { Text(stringResource(R.string.subscription_fetch_fragment_placeholder)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.mono(),
        shape = MaterialTheme.shapes.large,
        trailingIcon = {
            IconButton(onClick = { onSave(draft.ifBlank { null }) }) {
                Icon(
                    painter = painterResource(SubspaceIcons.Check),
                    contentDescription = stringResource(R.string.subscription_fetch_fragment_save),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** What the last refresh did to reach the subscription. Hosts only, never full URLs (ARCHITECTURE.md §5.6). */
@Composable
private fun ReachLines(state: SubscriptionDetailState) {
    state.movedFromHost?.let { host ->
        val text =
            state.movedAt?.let { stringResource(R.string.subscription_moved_from, host, relativeTime(it)) }
                ?: stringResource(R.string.subscription_moved_from_no_time, host)
        ReachFact(text)
    }
    if (state.showsRedirectsDenied) ReachFact(stringResource(R.string.subscription_redirects_denied))
    when (state.lastFetchVia) {
        FetchVia.Fallback -> ReachFact(stringResource(R.string.subscription_via_fallback))
        FetchVia.FallbackFailed -> ReachFact(stringResource(R.string.subscription_fallback_failed))
        FetchVia.Main, null -> Unit
    }
    if (state.insecureIgnored) IgnoredDirectiveText(stringResource(R.string.subscription_insecure_ignored))
    state.linkFragmentProblem?.let { problem ->
        val reason = stringResource(problem.textRes())
        IgnoredDirectiveText(stringResource(R.string.subscription_fetch_fragment_invalid, reason))
    }
    state.redirectIssues.forEach { issue -> IgnoredDirectiveText(redirectIssueText(issue)) }
    ResolverLines(state)
}

/**
 * M9-C spec §4.2. Trust is meaningful only while the provider's resolver is valid: for an absent or
 * invalid one the stored answer is not compared with any host, so it is not shown.
 */
@Composable
private fun ResolverLines(state: SubscriptionDetailState) {
    val provider = state.resolverProvider
    if (state.userPreResolveOn && provider !is ProviderResolver.Absent) {
        ReachFact(stringResource(R.string.subscription_resolver_user_applies))
    }
    if (!state.userPreResolveOn && state.resolverTrust == ResolverTrust.Denied && state.resolverHost != null) {
        ReachFact(stringResource(R.string.subscription_resolver_denied))
    }
    if (provider is ProviderResolver.Invalid) {
        IgnoredDirectiveText(stringResource(R.string.subscription_resolver_invalid))
    }
    // The user's own setting always carries a bootstrap IP, so this line is only true while it is Off.
    val providerInUse =
        (provider as? ProviderResolver.Valid)
            ?.takeIf { !state.userPreResolveOn && state.resolverTrust == ResolverTrust.Allowed }
    if (providerInUse != null && providerInUse.bootstrapIp == null) {
        ReachFact(stringResource(R.string.subscription_resolver_plain_bootstrap))
    }
}

@Composable
private fun ReachFact(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = ROW_GAP),
    )
}

@Composable
private fun redirectIssueText(issue: RedirectIssue): String =
    when {
        issue.superseded -> stringResource(R.string.subscription_redirect_superseded, issue.key)
        issue.collides -> stringResource(R.string.subscription_redirect_collides, issue.key)
        else -> {
            val reason = issue.reason?.let { stringResource(it.textRes()) }.orEmpty()
            stringResource(R.string.subscription_redirect_ignored, issue.key, reason)
        }
    }

private fun RedirectRejection.textRes(): Int =
    when (this) {
        RedirectRejection.Malformed -> R.string.redirect_rejection_malformed
        RedirectRejection.NotHttps -> R.string.redirect_rejection_not_https
        RedirectRejection.HasUserInfo -> R.string.redirect_rejection_user_info
        RedirectRejection.IpLiteral -> R.string.redirect_rejection_ip_literal
        RedirectRejection.LocalHost -> R.string.redirect_rejection_local
        RedirectRejection.NotABareHost -> R.string.redirect_rejection_bare_host
    }

@Composable
private fun DirectivesSection(
    state: SubscriptionDetailState,
    actions: SubscriptionDetailActions,
) {
    SectionCard(title = stringResource(R.string.subscription_detail_section_directives)) {
        state.fragmentIgnored?.let { problem ->
            val reason = stringResource(problem.textRes())
            IgnoredDirectiveText(stringResource(R.string.subscription_fragment_ignored, reason))
        }
        state.noiseIgnored?.let { problem ->
            val reason = stringResource(problem.textRes())
            IgnoredDirectiveText(stringResource(R.string.subscription_noise_ignored, reason))
        }
        TuningIssueLines(state.tuningIssues)
        state.rows.forEach { row ->
            DirectiveRowView(row = row, onPin = actions.onPinRow, onUnpin = actions.onUnpinRow)
        }
    }
}

/**
 * M9-E spec §3, §4, §7.1: each provider tuning directive that does not apply, worded by
 * [tuningDirectiveLine] as Settings → Tunnel words it. A value waiting for (or refused) consent is a
 * fact, not an error; an invalid one reads as an error, like the mask lines above.
 */
@Composable
private fun TuningIssueLines(issues: TuningDirectiveIssues) {
    listOfNotNull(issues.mux, issues.tunMtu, issues.excludedRoutes).forEach { issue ->
        val text = tuningDirectiveLine(issue.key, issue.problem, issue.partial)
        if (tuningIgnoreKind(issue.problem, issue.partial) == TuningIgnoreKind.NotApplied) {
            ReachFact(text)
        } else {
            IgnoredDirectiveText(text)
        }
    }
    // Which range covers the DNS server in use is only known to :bg at connect (M9-E spec §4).
    if (issues.excludedRoutesApply) ReachFact(stringResource(R.string.subscription_route_exclusion_applies))
}

@Composable
private fun IgnoredDirectiveText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = FIELD_GAP),
    )
}

/** A [SectionTitle] over a `surfaceContainer` card — the same shape the server editor's sections use. */
@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(title)
        Surface(
            shape = SubspaceShapeTokens.card,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(CONTENT_PADDING),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP),
            ) {
                content()
            }
        }
    }
}

/**
 * A read-only row with an optional [trailing] slot, for what [HubRow] (navigates) and
 * [SwitchRow] (toggles) do not cover. [mono] renders [supporting] as a machine string, selectable.
 */
@Suppress("LongParameterList")
@Composable
private fun InfoRow(
    @DrawableRes icon: Int,
    label: String,
    supporting: String?,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CONTENT_PADDING),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (supporting != null) {
                val style = MaterialTheme.typography.bodyMedium.let { if (mono) it.mono() else it }
                val supportingText: @Composable () -> Unit = {
                    Text(text = supporting, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (mono) SelectionContainer { supportingText() } else supportingText()
            }
        }
        trailing()
    }
}

@Composable
private fun UrlRow(
    redactedUrl: String,
    modifier: Modifier = Modifier,
) {
    InfoRow(
        icon = SubspaceIcons.Public,
        label = stringResource(R.string.subscription_detail_url_label),
        supporting = redactedUrl,
        mono = true,
        modifier = modifier,
    )
}

@Composable
private fun LastFetchRow(
    state: SubscriptionDetailState,
    onRefreshNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InfoRow(
        icon = SubspaceIcons.Sync,
        label = lastFetchLabel(state.lastFetchState),
        supporting = lastFetchSupportingText(state.lastFetchState),
        trailing = { RefreshTrailing(isRefreshing = state.isRefreshing, onRefreshNow = onRefreshNow) },
        modifier = modifier,
    )
}

@Composable
private fun RefreshTrailing(
    isRefreshing: Boolean,
    onRefreshNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isRefreshing) {
        val refreshingDescription = stringResource(R.string.subscription_detail_refreshing_description)
        CircularProgressIndicator(
            modifier =
            modifier
                .size(24.dp)
                .semantics { contentDescription = refreshingDescription },
        )
    } else {
        IconButton(onClick = onRefreshNow, modifier = modifier) {
            Icon(
                painter = painterResource(SubspaceIcons.Refresh),
                contentDescription = stringResource(R.string.subscription_detail_refresh_action),
            )
        }
    }
}

@Composable
private fun lastFetchLabel(state: LastFetchState): String =
    when (state) {
        LastFetchState.NeverFetched -> stringResource(R.string.subscription_detail_never_fetched)
        is LastFetchState.Succeeded ->
            stringResource(R.string.subscription_detail_last_fetched, relativeTime(state.atEpochMillis))
        is LastFetchState.Failed -> userMessageText(state.reason.toUserMessage())
        is LastFetchState.NoServers -> stringResource(R.string.subscription_error_no_servers)
    }

@Composable
private fun lastFetchSupportingText(state: LastFetchState): String? =
    when (state) {
        is LastFetchState.Failed ->
            state.lastSuccessAtEpochMillis?.let {
                stringResource(R.string.subscription_detail_last_succeeded, relativeTime(it))
            }
                ?: stringResource(R.string.subscription_detail_never_succeeded)
        is LastFetchState.NoServers ->
            state.lastSuccessAtEpochMillis?.let {
                stringResource(R.string.subscription_detail_last_succeeded, relativeTime(it))
            }
                ?: stringResource(R.string.subscription_detail_never_succeeded)
        else -> null
    }

@Composable
private fun relativeTime(epochMillis: Long): String =
    DateUtils
        .getRelativeTimeSpanString(epochMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        .toString()

/**
 * The most recent manual [SubscriptionDetailActions.onRefreshNow] result — tapping it dismisses
 * it. No auto-dismiss timer: a failure reason the user did not get to read defeats the point of
 * showing it, and the next refresh already clears this up front regardless.
 *
 * A message with a [UserMessage.quantity] or the up-to-date string is a success; anything else
 * is one of the closed failure strings and reads as blocked.
 */
@Composable
private fun RefreshResultBanner(
    message: UserMessage,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val succeeded = message.quantity != null || message.resId == R.string.subscription_up_to_date
    TonalBanner(
        icon = if (succeeded) SubspaceIcons.CheckFilled else SubspaceIcons.Warning,
        text = userMessageText(message),
        tone = if (succeeded) BannerTone.Neutral else BannerTone.Blocked,
        modifier = modifier.clickable(onClick = onDismiss),
    )
}

/**
 * Everything this subscription tells its provider about the user and the device (M10-B spec §3.5):
 * the HWID, the four device headers, the User-Agent and how long to wait for a reply.
 */
@Composable
private fun SentToProviderSection(
    state: SubscriptionDetailState,
    actions: SubscriptionDetailActions,
) {
    SectionCard(title = stringResource(R.string.subscription_detail_section_sent)) {
        HwidBlock(state = state, actions = actions)
        DeviceHeader.entries.forEach { header ->
            val row = state.headers[header] ?: return@forEach
            HeaderBlock(header = header, row = row, actions = actions)
        }
        UserAgentRow(
            value = state.userAgentOverride,
            providerValue = state.providerUserAgent,
            onSave = actions.onUserAgentOverrideChanged,
        )
        state.timeoutRow?.let { DirectiveRowView(row = it, onPin = actions.onPinRow, onUnpin = actions.onUnpinRow) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HwidBlock(
    state: SubscriptionDetailState,
    actions: SubscriptionDetailActions,
) {
    var editing by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.subscription_detail_hwid_label)
    SwitchRow(
        icon = SubspaceIcons.Lock,
        label = label,
        supporting = stringResource(R.string.subscription_detail_hwid_description),
        checked = state.hwidEnabled,
        onCheckedChange = actions.onHwidEnabledChanged,
    )
    if (editing) {
        // A machine string: displayed, never logged (ARCHITECTURE.md §5.6).
        OutlinedTextField(
            value = state.hwidDraft,
            onValueChange = actions.onHwidDraftChanged,
            label = { Text(label) },
            supportingText = {
                Text(stringResource(R.string.subscription_detail_hwid_format))
            },
            isError = state.hwidError,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.mono(),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().testTag(SUBSCRIPTION_HWID_FIELD_TEST_TAG),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            TextButton(onClick = { if (actions.onHwidSave()) editing = false }) {
                Text(stringResource(R.string.subscription_detail_hwid_save))
            }
            TextButton(
                onClick = {
                    actions.onHwidDraftChanged(state.hwid)
                    editing = false
                },
            ) { Text(stringResource(R.string.subscription_row_edit_dialog_cancel)) }
        }
    } else {
        SelectionContainer {
            Text(
                text = state.hwid,
                style = MaterialTheme.typography.bodyMedium.mono(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = HWID_INDENT).testTag(SUBSCRIPTION_HWID_VALUE_TEST_TAG),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            TextButton(onClick = { editing = true }) { Text(stringResource(R.string.subscription_detail_hwid_edit)) }
            TextButton(onClick = actions.onHwidRandom) {
                Text(stringResource(R.string.subscription_detail_hwid_random))
            }
            TextButton(
                onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, state.hwid))) }
                },
            ) { Text(stringResource(R.string.subscription_detail_hwid_copy)) }
        }
    }
}

/** One device header: its switch, its value or "follows this device", and Edit and Reset. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderBlock(
    header: DeviceHeader,
    row: HeaderRowState,
    actions: SubscriptionDetailActions,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    val followed = row.literal == null
    // Header values are identifying: displayed, never logged (ARCHITECTURE.md §5.6).
    val supporting =
        when {
            !followed -> row.literal
            row.deviceValue != null -> stringResource(R.string.subscription_detail_header_follows, row.deviceValue)
            else -> null
        }
    SwitchRow(
        icon = SubspaceIcons.Tune,
        label = header.wireName,
        supporting = supporting,
        checked = row.enabled,
        onCheckedChange = { actions.onHeaderEnabledChanged(header, it) },
        modifier = Modifier.testTag(headerTestTag(header)),
    )
    if (row.enabled && followed && row.deviceValue == null) {
        ReachFact(stringResource(R.string.subscription_detail_header_not_sent))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        TextButton(onClick = { dialogOpen = true }) { Text(stringResource(R.string.subscription_detail_header_edit)) }
        if (!followed) {
            TextButton(onClick = { actions.onHeaderReset(header) }) {
                Text(stringResource(R.string.subscription_detail_header_reset))
            }
        }
    }
    if (dialogOpen) {
        HeaderValueDialog(
            header = header,
            initialValue = row.literal ?: row.deviceValue.orEmpty(),
            onSave = actions.onHeaderLiteralSave,
            onDismiss = { dialogOpen = false },
        )
    }
}

@Composable
private fun HeaderValueDialog(
    header: DeviceHeader,
    initialValue: String,
    onSave: (DeviceHeader, String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    var refused by remember { mutableStateOf(false) }
    SubspaceDialog(
        title = header.wireName,
        confirmLabel = stringResource(R.string.subscription_detail_hwid_save),
        onConfirm = { if (onSave(header, text)) onDismiss() else refused = true },
        dismissLabel = stringResource(R.string.subscription_row_edit_dialog_cancel),
        onDismiss = onDismiss,
        confirmEnabled = text.isNotBlank(),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                refused = false
            },
            supportingText = { Text(stringResource(R.string.subscription_detail_header_format)) },
            isError = refused,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.mono(),
            modifier = Modifier.testTag(SUBSCRIPTION_HEADER_FIELD_TEST_TAG),
        )
    }
}

@Composable
private fun UserAgentRow(
    value: String?,
    providerValue: String?,
    onSave: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(value) { mutableStateOf(value.orEmpty()) }
    // A machine string: displayed, never logged (ARCHITECTURE.md §5.6).
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(stringResource(R.string.subscription_detail_user_agent_label)) },
        supportingText = providerValue?.let { provider ->
            {
                Text(
                    text = stringResource(R.string.subscription_detail_user_agent_provider, provider),
                    style = MaterialTheme.typography.bodySmall.mono(),
                )
            }
        },
        placeholder = { Text(stringResource(R.string.subscription_detail_user_agent_placeholder)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.mono(),
        shape = MaterialTheme.shapes.large,
        trailingIcon = {
            IconButton(onClick = { onSave(draft.ifBlank { null }) }) {
                Icon(
                    painter = painterResource(SubspaceIcons.Check),
                    contentDescription = stringResource(R.string.subscription_detail_user_agent_save),
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A directive: a [SwitchRow] for a toggle, a [HubRow] that opens the pin dialog for a number.
 * A pinned directive also carries an unpin button beside the row.
 */
@Composable
private fun DirectiveRowView(
    row: DirectiveRow,
    onPin: (String, String) -> Unit,
    onUnpin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(row.labelRes)
    val supporting = directiveSupportingText(row)
    var dialogOpen by remember { mutableStateOf(false) }

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        when (row.kind) {
            DirectiveRowKind.Toggle ->
                SwitchRow(
                    icon = SubspaceIcons.Tune,
                    label = label,
                    supporting = supporting,
                    checked = row.row.isEnabled,
                    onCheckedChange = { checked -> onPin(row.row.key, if (checked) "true" else "false") },
                    modifier = Modifier.weight(1f),
                )
            DirectiveRowKind.Hours, DirectiveRowKind.Seconds ->
                HubRow(
                    icon = SubspaceIcons.Tune,
                    label = label,
                    value = supporting,
                    onClick = { dialogOpen = true },
                    modifier = Modifier.weight(1f),
                    // "Provider suggests 6 h · you pinned 24 h": both halves matter, so it wraps.
                    valueMaxLines = Int.MAX_VALUE,
                )
        }
        if (row.row.isPinned) UnpinButton(key = row.row.key, onUnpin = onUnpin)
    }

    if (dialogOpen) {
        PinValueDialog(
            label = label,
            initialValue = row.row.value.orEmpty(),
            onConfirm = { newValue ->
                dialogOpen = false
                onPin(row.row.key, newValue)
            },
            onDismiss = { dialogOpen = false },
        )
    }
}

@Composable
private fun directiveSupportingText(row: DirectiveRow): String {
    val formattedValue = row.row.value?.let { formatDirectiveValue(row.kind, it) }.orEmpty()
    if (!row.row.showsDeclinedProviderValue) return formattedValue
    val declined = row.row.declinedProviderValue?.let { formatDirectiveValue(row.kind, it) }.orEmpty()
    return stringResource(R.string.subscription_row_pinned_declined, declined, formattedValue)
}

@Composable
private fun formatDirectiveValue(
    kind: DirectiveRowKind,
    raw: String,
): String =
    when (kind) {
        DirectiveRowKind.Hours -> stringResource(R.string.subscription_row_value_hours, raw)
        DirectiveRowKind.Seconds -> stringResource(R.string.subscription_row_value_seconds, raw)
        DirectiveRowKind.Toggle ->
            stringResource(
                if (isDirectiveEnabled(raw)) {
                    R.string.subscription_row_value_on
                } else {
                    R.string.subscription_row_value_off
                },
            )
    }

@Composable
private fun UnpinButton(
    key: String,
    onUnpin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = { onUnpin(key) }, modifier = modifier) {
        Icon(
            painter = painterResource(SubspaceIcons.Close),
            contentDescription = stringResource(R.string.subscription_row_unpin_action),
        )
    }
}

@Composable
private fun PinValueDialog(
    label: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    SubspaceDialog(
        title = label,
        confirmLabel = stringResource(R.string.subscription_row_pin_action),
        onConfirm = { onConfirm(text) },
        dismissLabel = stringResource(R.string.subscription_row_edit_dialog_cancel),
        onDismiss = onDismiss,
        confirmEnabled = text.isNotBlank(),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

@Composable
private fun DeleteButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(SubspaceIcons.Delete),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.width(FIELD_GAP))
        Text(text = stringResource(R.string.subscription_detail_delete_action), color = MaterialTheme.colorScheme.error)
    }
}

/** Warns before a subscription is removed — this cascades to its group and every server in it. */
@Composable
private fun DeleteSubscriptionDialog(
    name: String,
    profileCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SubspaceDialog(
        title = stringResource(R.string.subscription_detail_delete_confirm_title, name),
        body = pluralStringResource(R.plurals.subscription_detail_delete_confirm_body, profileCount, profileCount),
        confirmLabel = stringResource(R.string.subscription_detail_delete_confirm_action),
        onConfirm = onConfirm,
        dismissLabel = stringResource(R.string.subscription_detail_delete_cancel_action),
        onDismiss = onDismiss,
        icon = SubspaceIcons.Delete,
        destructive = true,
    )
}
