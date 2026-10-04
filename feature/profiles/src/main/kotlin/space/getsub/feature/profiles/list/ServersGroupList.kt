// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the empty-state's content padding — a
// tokens/spacing.css --space-* value, already named by the val it
// initializes. See core/ui's GroupCard.kt for the same pattern.
@file:Suppress("MagicNumber")

package space.getsub.feature.profiles.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.component.FLOATING_NAV_CONTENT_BOTTOM_PADDING
import space.getsub.core.ui.component.GroupCard
import space.getsub.core.ui.component.GroupCardUpdateRow
import space.getsub.core.ui.component.MenuItem
import space.getsub.core.ui.component.NoteTone
import space.getsub.core.ui.component.QuotaBar
import space.getsub.core.ui.component.ServerRow
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.feature.profiles.R
import space.getsub.core.ui.R as CoreR

private val LIST_TOP_PADDING = 8.dp
private val GROUP_GAP = 8.dp
private val MEASURE_BAR_GAP = 8.dp
private val GROUP_HEADER_GAP = 8.dp
private val ROW_GAP = 8.dp
private val MARKER_GAP = 4.dp
private val SMALL_ICON_SIZE = 18.dp
private val MARKER_ICON_SIZE = 14.dp
private val EMPTY_ICON_SIZE = 48.dp
private val EMPTY_TOP_PADDING = 48.dp
private val EMPTY_GAP = 8.dp

/**
 * [ServersGroupList]'s callbacks, grouped for the same reason
 * [ServersActions] is.
 */
internal data class ServersGroupListActions(
    val onToggleExpand: (Long) -> Unit,
    val onRename: (ServersGroup) -> Unit,
    val onDelete: (ServersGroup) -> Unit,
    val onAddProfile: () -> Unit,
    val onProfileSelected: (Long) -> Unit,
    /** Task 21: the row's edit icon — opens `Editor(profileId)`, distinct from [onProfileSelected]. */
    val onProfileEdit: (Long) -> Unit,
    /**
     * Fix round, Important 1: `GroupCard`'s Update button — runs one sync of
     * the subscription id [ServersGroup.subscriptionId] names. Only ever
     * invoked for a `SUBSCRIPTION` group, since [subscriptionId] is null for
     * a `MANUAL` one and the call site below never builds a `GroupCard`
     * `onUpdate` lambda in that case.
     */
    val onUpdateSubscription: (Long) -> Unit,
    /**
     * Task 15: `GroupCard`'s "Subscription details" overflow item — opens
     * `SubscriptionDetail(subscriptionId)`. Same "only ever invoked for a
     * `SUBSCRIPTION` group" reasoning as [onUpdateSubscription].
     */
    val onOpenSubscriptionDetail: (Long) -> Unit,
    /** A row's test button — measures that one server. */
    val onTestProfile: (Long) -> Unit,
    /** A group's "Test all" — measures the rows currently visible in it. */
    val onTestGroup: (Long) -> Unit,
    /** Stops the run in flight. Rows still testing return to idle, not to a result. */
    val onCancelTests: () -> Unit,
    /** This group's own order, which outranks its provider's (§A.1). */
    val onGroupSortChanged: (Long, SortOrder) -> Unit,
)

/**
 * Every group as a [GroupCard], or the empty state when [groups] is empty —
 * a genuinely empty store (nothing imported yet), not a search/filter that
 * happens to match nothing (a group with zero matching rows still renders,
 * with an empty [ServersGroup.profiles] list — see that property's own KDoc).
 */
@Composable
internal fun ServersGroupList(
    groups: List<ServersGroup>,
    expandedGroups: Set<Long>,
    actions: ServersGroupListActions,
    modifier: Modifier = Modifier,
) {
    if (groups.isEmpty()) {
        EmptyServersState(onAddProfile = actions.onAddProfile, modifier = modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding =
        PaddingValues(
            top = LIST_TOP_PADDING,
            // The list scrolls, so it — not the caller's screen host —
            // reserves the space the floating nav pill floats over. See
            // FLOATING_NAV_CONTENT_BOTTOM_PADDING's own KDoc.
            bottom = FLOATING_NAV_CONTENT_BOTTOM_PADDING,
        ),
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
    ) {
        // A search or protocol filter that hides every row: say so once, rather than leaving
        // the cards looking broken. (A group with no rows and no filter has totalProfileCount 0.)
        if (groups.all { it.profiles.isEmpty() } && groups.any { it.totalProfileCount > 0 }) {
            item(key = "no-match") { NoMatchLine() }
        }
        items(groups, key = { it.id }) { group ->
            val menuItems = groupMenuItems(group, actions)
            GroupCard(
                title = group.name,
                subtitle =
                pluralStringResource(
                    CoreR.plurals.group_card_profile_count,
                    group.totalProfileCount,
                    group.totalProfileCount,
                ),
                expanded = group.id in expandedGroups,
                onToggle = { actions.onToggleExpand(group.id) },
                menuItems = menuItems,
                header = {
                    QuotaBar(
                        usedBytes = group.quotaUsedBytes,
                        totalBytes = group.quotaTotalBytes,
                        modifier = Modifier.padding(top = GROUP_HEADER_GAP),
                    )
                    // onUpdate is null for a MANUAL group (subscriptionId is null there) —
                    // no Update affordance without an id to sync.
                    GroupCardUpdateRow(
                        groupName = group.name,
                        lastFetchedAtEpochMillis = group.lastFetchedAtEpochMillis,
                        onUpdate = group.subscriptionId?.let { id -> { actions.onUpdateSubscription(id) } },
                    )
                },
            ) {
                // Nothing to test or sort when the filter leaves this group without rows.
                if (group.profiles.isNotEmpty()) {
                    GroupMeasureBar(
                        group = group,
                        onTestGroup = { actions.onTestGroup(group.id) },
                        onCancelTests = actions.onCancelTests,
                        onSortChanged = { order -> actions.onGroupSortChanged(group.id, order) },
                    )
                    ServerRows(group = group, actions = actions)
                }
            }
        }
    }
}

/**
 * The overflow menu of a [GroupCard]: rename, delete and add-profile for every
 * group, plus "Subscription details" for a SUBSCRIPTION one (a MANUAL group has
 * no detail screen, and its [ServersGroup.subscriptionId] is null).
 */
@Composable
private fun groupMenuItems(
    group: ServersGroup,
    actions: ServersGroupListActions,
): List<MenuItem> =
    buildList {
        add(MenuItem(SubspaceIcons.Edit, stringResource(CoreR.string.group_card_rename)) { actions.onRename(group) })
        add(
            MenuItem(SubspaceIcons.Delete, stringResource(CoreR.string.group_card_delete), destructive = true) {
                actions.onDelete(group)
            },
        )
        add(MenuItem(SubspaceIcons.Add, stringResource(CoreR.string.group_card_add_profile)) { actions.onAddProfile() })
        group.subscriptionId?.let { id ->
            add(
                MenuItem(SubspaceIcons.OpenInNew, stringResource(CoreR.string.group_card_open_detail)) {
                    actions.onOpenSubscriptionDetail(id)
                },
            )
        }
    }

@Composable
private fun NoMatchLine(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.servers_no_match),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = EMPTY_GAP),
    )
}

/** One [ServerRow] per visible profile of [group], with this group's ranks (M8.6 Part 3 R4). */
@Composable
private fun ServerRows(
    group: ServersGroup,
    actions: ServersGroupListActions,
) {
    val ranks = group.profiles.ranks()
    group.profiles.forEach { row ->
        ServerRow(
            flag = row.leadingMark(),
            name = row.displayName(),
            detail = "${row.protocolDisplay} · ${row.transport}",
            latencyText = pillText(row.pill()),
            selected = row.isActive,
            // Dims the row and disables its latency pill only; core's ServerRow keeps an
            // unsupported row selectable (M8.6 Part 3 review focus 4).
            enabled = row.connectable,
            rank = ranks[row.id],
            note = row.note()?.let { noteText(it) },
            noteTone = row.note()?.tone() ?: NoteTone.Error,
            onSelect = { actions.onProfileSelected(row.id) },
            onTestLatency = { actions.onTestProfile(row.id) },
            onEdit = { actions.onProfileEdit(row.id) },
            modifier = Modifier.padding(top = ROW_GAP),
        )
    }
}

/** The pill's text for [pill]; [PillText.Ms] is the only branch that carries a number (M8.5 spec §10.1). */
@Composable
private fun pillText(pill: PillText): String =
    when (pill) {
        PillText.Testing -> stringResource(R.string.servers_latency_testing)
        PillText.None -> stringResource(R.string.servers_latency_none)
        is PillText.Ms -> stringResource(R.string.servers_latency_ms, pill.ms)
        PillText.Failed -> stringResource(R.string.servers_latency_failed)
        PillText.Unsupported -> stringResource(R.string.servers_latency_unsupported)
        PillText.ForeignVpn -> stringResource(R.string.servers_latency_foreign_vpn)
    }

@Composable
private fun noteText(note: RowNote): String =
    stringResource(
        when (note) {
            RowNote.NotConnectable -> R.string.servers_not_connectable
            RowNote.Dropped -> R.string.servers_no_longer_offered
            RowNote.Compatibility -> R.string.servers_compatibility_mode
        },
    )

/**
 * This group's measurement and ordering controls.
 *
 * Sort lives here, per group, rather than only on the screen: §A.1 scopes
 * `subscriptions-sort-type` to the subscription that delivered it, so one global
 * control would let provider A rearrange provider B's rows. When the order came
 * from the provider the group says so — §A.1 requires that precedence to be
 * *visible*, not merely correct — and picking an order here clears the marker,
 * because the user's own choice outranks it. The line under the bar says what the
 * marker means: only the *default* sort is ignored, a per-group pick still wins
 * (M8.6 Part 3 R5).
 *
 * The button flips to "Stop testing" while any of this group's rows are in
 * flight. Cancelling is not instantaneous (a measurement already inside
 * libXray's blocking ping finishes on its own), so the rows it stops simply
 * return to idle rather than showing a result that never arrived.
 */
@Composable
private fun GroupMeasureBar(
    group: ServersGroup,
    onTestGroup: () -> Unit,
    onCancelTests: () -> Unit,
    onSortChanged: (SortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val testing = group.profiles.any { it.isTesting }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MEASURE_BAR_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = if (testing) onCancelTests else onTestGroup) {
                Icon(
                    painterResource(if (testing) SubspaceIcons.StopCircle else SubspaceIcons.Speed),
                    contentDescription = null,
                    modifier = Modifier.size(SMALL_ICON_SIZE),
                )
                Text(
                    text = stringResource(if (testing) R.string.servers_test_cancel else R.string.servers_test_group),
                    modifier = Modifier.padding(start = MEASURE_BAR_GAP / 2),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            GroupSortChip(sort = group.sort, onSortChanged = onSortChanged)
        }
        if (group.showsProviderOrder()) {
            // Under the bar rather than in it: marker, test button and sort chip together
            // do not fit a 360 dp card, and the chip's label wrapped to two lines.
            ProviderOrderMarker()
            Text(
                text = stringResource(R.string.servers_sort_provider_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProviderOrderMarker(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MARKER_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(SubspaceIcons.CloudDownload),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(MARKER_ICON_SIZE),
        )
        Text(
            text = stringResource(R.string.servers_sort_provider_marker),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** This group's own sort, as an assist chip over [SortMenu] (same shape as the default-sort chip). */
@Composable
private fun GroupSortChip(
    sort: SortOrder,
    onSortChanged: (SortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        AssistChip(
            onClick = { menuOpen = true },
            label = { Text(stringResource(sort.labelRes()), maxLines = 1, softWrap = false) },
            trailingIcon = {
                Icon(
                    painterResource(SubspaceIcons.ExpandMore),
                    contentDescription = null,
                    modifier = Modifier.size(SMALL_ICON_SIZE),
                )
            },
        )
        SortMenu(expanded = menuOpen, current = sort, onDismiss = { menuOpen = false }, onPick = onSortChanged)
    }
}

@Composable
private fun EmptyServersState(
    onAddProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = EMPTY_TOP_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(EMPTY_GAP),
    ) {
        Icon(
            painterResource(SubspaceIcons.Dns),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(EMPTY_ICON_SIZE),
        )
        Text(
            text = stringResource(R.string.servers_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.servers_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAddProfile) {
            Text(stringResource(R.string.servers_empty_action))
        }
    }
}
