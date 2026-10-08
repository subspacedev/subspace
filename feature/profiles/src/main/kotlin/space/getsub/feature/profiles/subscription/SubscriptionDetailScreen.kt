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

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import space.getsub.core.data.isDirectiveEnabled
import space.getsub.core.ui.component.BannerTone
import space.getsub.core.ui.component.HubRow
import space.getsub.core.ui.component.QuotaBar
import space.getsub.core.ui.component.SectionTitle
import space.getsub.core.ui.component.SubspaceDialog
import space.getsub.core.ui.component.SwitchRow
import space.getsub.core.ui.component.TonalBanner
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.SubspaceShapeTokens
import space.getsub.core.ui.theme.mono
import space.getsub.feature.profiles.R
import space.getsub.feature.profiles.add.UserMessage
import space.getsub.feature.profiles.add.toUserMessage
import space.getsub.feature.profiles.add.userMessageText

private val CONTENT_PADDING = 16.dp
private val FIELD_GAP = 12.dp
private val ROW_GAP = 4.dp

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
            onUserAgentOverrideChanged = viewModel::onUserAgentOverrideChanged,
            onDeleteConfirmed = viewModel::onDelete,
        ),
        modifier = modifier,
    )
}

/**
 * [SubscriptionDetailContent]'s eight callbacks, grouped for the same reason
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
        SectionCard(title = stringResource(R.string.subscription_detail_section_requests)) {
            HwidRow(
                checked = state.hwidEnabled,
                globallyEnabled = state.globalHwidEnabled,
                onCheckedChange = actions.onHwidEnabledChanged,
            )
            UserAgentRow(
                value = state.userAgentOverride,
                providerValue = state.providerUserAgent,
                onSave = actions.onUserAgentOverrideChanged,
            )
        }
        if (state.rows.isNotEmpty()) {
            SectionCard(title = stringResource(R.string.subscription_detail_section_directives)) {
                state.rows.forEach { row ->
                    DirectiveRowView(row = row, onPin = actions.onPinRow, onUnpin = actions.onUnpinRow)
                }
            }
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

@Composable
private fun HwidRow(
    checked: Boolean,
    globallyEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description =
        stringResource(
            if (globallyEnabled) {
                R.string.subscription_detail_hwid_description
            } else {
                R.string.subscription_detail_hwid_disabled_globally
            },
        )
    SwitchRow(
        icon = SubspaceIcons.Lock,
        label = stringResource(R.string.subscription_detail_hwid_label),
        supporting = description,
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = globallyEnabled,
        modifier = modifier,
    )
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
