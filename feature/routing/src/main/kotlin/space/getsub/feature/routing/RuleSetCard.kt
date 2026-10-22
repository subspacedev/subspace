// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the padding/size tokens this file reads —
// all are declared in RoutingListScreen.kt alongside the rest of the scale.
@file:Suppress("MagicNumber")

package space.getsub.feature.routing

import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import space.getsub.core.model.DnsState
import space.getsub.core.model.RoutingSourceKind
import space.getsub.core.model.RuleSetAssetFailure
import space.getsub.core.model.RuleSetAssetState
import space.getsub.core.ui.component.BannerTone
import space.getsub.core.ui.component.MenuItem
import space.getsub.core.ui.component.SubspaceMenu
import space.getsub.core.ui.component.TonalBanner
import space.getsub.core.ui.component.WavyProgress
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.SubspaceShapeTokens

/**
 * One rule set's card, split out of [RoutingListScreen] once M6 gave each row a
 * provenance badge, its own generation state and a cancellable download — the
 * same extraction [RoutingListScreen]'s `routingDestinations` counterpart in
 * `:app` documents, and for the same reason: a file that renders one row well
 * is easier to reason about than one that renders a screen and a row.
 */

/** One card's callbacks, grouped for the same reason [RoutingListActions] is. */
/**
 * This file's own copy of the spacing scale. Every screen in this module
 * declares its own `private` copy of the tokens it reads — `PerAppScreen` and
 * `RuleSetEditorScreen` do the same — so a shared `internal` set would collide
 * with all of them rather than unify them.
 */
private val CARD_PADDING = 16.dp
private val ROW_ICON_GAP = 8.dp
private val MARKER_TOP_PADDING = 8.dp

internal data class RuleSetCardActions(
    val onSelect: () -> Unit,
    val onEdit: () -> Unit,
    val onDuplicate: () -> Unit,
    val onDelete: () -> Unit,
    val onCancelDownload: () -> Unit,
)

@Composable
internal fun RuleSetCard(
    row: RuleSetRow,
    actions: RuleSetCardActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = SubspaceShapeTokens.cardLead,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(CARD_PADDING)) {
            RuleSetCardHeader(row = row, actions = actions)

            if (row.missingGeoFiles.isNotEmpty()) {
                val missing = row.missingGeoFiles.sorted().joinToString()
                Banner(
                    text = stringResource(R.string.routing_missing_geo_files, missing),
                    tone = BannerTone.Blocked,
                )
            }
            if (row.hasFailedGeoUpdate) {
                Banner(text = stringResource(R.string.routing_update_failed))
            }
            // Three independent axes, never collapsed into one: missingGeoFiles
            // above blocks activation, hasFailedGeoUpdate reports a refresh of
            // an installed file, and this reports *this set's own* generation.
            AssetStateRow(row = row, onCancelDownload = actions.onCancelDownload)
            when (row.dnsState) {
                DnsState.None -> Unit
                DnsState.Applied ->
                    Banner(text = stringResource(R.string.routing_dns_applied), icon = SubspaceIcons.Dns)
                DnsState.Invalid ->
                    Banner(text = stringResource(R.string.routing_dns_invalid), icon = SubspaceIcons.Warning)
            }
        }
    }
}

/** The card's selector, labels and per-row actions. */
@Composable
private fun RuleSetCardHeader(
    row: RuleSetRow,
    actions: RuleSetCardActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val activateDescription = stringResource(R.string.routing_activate_description, row.name)
        RadioButton(
            selected = row.isActive,
            // A disabled control is a hint, not the gate — RoutingViewModel.activate
            // re-checks canActivate itself, so this only saves the user a tap that
            // would be silently refused.
            enabled = row.canActivate,
            onClick = actions.onSelect,
            modifier = Modifier.semantics { contentDescription = activateDescription },
        )
        Spacer(Modifier.width(ROW_ICON_GAP))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = pluralStringResource(R.plurals.routing_entry_count, row.entryCount, row.entryCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = row.provenanceLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CardMenu(row = row, actions = actions)
    }
}

/**
 * Edit / Duplicate / Delete in one overflow menu (M8.6 spec §4.5).
 *
 * A read-only row gets Duplicate in place of Edit, not beside it: the next sync overwrites that
 * set, so an edit made here would silently disappear (M6 spec §4.2). Delete only asks for
 * confirmation; the list screen owns the dialog.
 */
@Composable
private fun CardMenu(
    row: RuleSetRow,
    actions: RuleSetCardActions,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(SubspaceIcons.MoreVert),
                contentDescription = stringResource(R.string.routing_more_description, row.name),
            )
        }
        SubspaceMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            items =
            listOf(
                if (row.isReadOnly) {
                    MenuItem(
                        icon = SubspaceIcons.ContentCopy,
                        label = stringResource(R.string.routing_duplicate_label),
                        onClick = actions.onDuplicate,
                    )
                } else {
                    MenuItem(
                        icon = SubspaceIcons.Edit,
                        label = stringResource(R.string.routing_edit_label),
                        onClick = actions.onEdit,
                    )
                },
                MenuItem(
                    icon = SubspaceIcons.Delete,
                    label = stringResource(R.string.routing_delete_label),
                    destructive = true,
                    onClick = actions.onDelete,
                ),
            ),
        )
    }
}

/** The provenance badge — spec §9's first row addition. */
@Composable
private fun RuleSetRow.provenanceLabel(): String =
    when (sourceKind) {
        null -> stringResource(R.string.routing_source_made_here)
        RoutingSourceKind.Deeplink -> stringResource(R.string.routing_source_deeplink)
        RoutingSourceKind.Qr -> stringResource(R.string.routing_source_qr)
        RoutingSourceKind.Clipboard -> stringResource(R.string.routing_source_clipboard)
        RoutingSourceKind.Conversion -> stringResource(R.string.routing_source_conversion)
        RoutingSourceKind.Header,
        RoutingSourceKind.Body,
        ->
            subscriptionName?.let { stringResource(R.string.routing_source_subscription, it) }
                ?: stringResource(R.string.routing_source_provider)
    }

/**
 * This set's own generation state, and the cancel that stops it.
 *
 * A live [RuleSetRow.downloadProgress] wins over [RuleSetRow.assetState]: the
 * row is `Pending` for the whole materialisation, but only part of it is
 * actually moving bytes, and a byte count is the more specific truth while it
 * lasts.
 */
@Composable
private fun AssetStateRow(
    row: RuleSetRow,
    onCancelDownload: () -> Unit,
) {
    val progress = row.downloadProgress
    if (progress != null) {
        val context = LocalContext.current
        val downloaded = Formatter.formatShortFileSize(context, progress.downloadedBytes)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = MARKER_TOP_PADDING),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                    progress.totalBytes?.let { total ->
                        stringResource(
                            R.string.routing_downloading,
                            downloaded,
                            Formatter.formatShortFileSize(context, total),
                        )
                    } ?: stringResource(R.string.routing_downloading_unknown_total, downloaded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // An undeclared total cannot drive a determinate bar; showing
                // one anyway would invent a fraction out of nothing.
                WavyProgress(
                    fraction =
                    progress.totalBytes?.takeIf { it > 0 }?.let { progress.downloadedBytes.toFloat() / it.toFloat() },
                    modifier = Modifier.padding(top = MARKER_TOP_PADDING),
                )
            }
            IconButton(onClick = onCancelDownload) {
                Icon(
                    painter = painterResource(SubspaceIcons.Close),
                    contentDescription = stringResource(R.string.routing_download_cancel_description, row.name),
                )
            }
        }
        return
    }
    // A literal-only set has no geo files, so none of these lines apply to it.
    // It reported "Geo files ready" about files it does not have.
    if (!row.requiresGeoFiles && row.assetState != RuleSetAssetState.Failed) return
    when (row.assetState) {
        RuleSetAssetState.None -> Unit
        // Plain text, no warning glyph: these are ordinary states, and
        // a banner's icon made a working profile look like it had a problem.
        RuleSetAssetState.Ready -> AssetStateNote(R.string.routing_asset_ready)
        RuleSetAssetState.Pending -> {
            Banner(text = stringResource(R.string.routing_asset_pending), icon = SubspaceIcons.Sync)
            WavyProgress(fraction = null, modifier = Modifier.padding(top = MARKER_TOP_PADDING))
        }
        RuleSetAssetState.Failed ->
            Banner(text = stringResource(row.assetFailure.messageRes()), tone = BannerTone.Blocked)
    }
}

/** One non-alarming asset-state line. [Banner] is for the states that warrant a warning. */
@Composable
private fun AssetStateNote(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = MARKER_TOP_PADDING),
    )
}

/**
 * A [RuleSetAssetState.Failed] row always carries a failure — the repository
 * writes the pair in one statement — so null here can only be a future state
 * this `when` has not learned yet. It gets the least specific of the reasons
 * rather than a claim the row cannot support.
 */
private fun RuleSetAssetFailure?.messageRes(): Int =
    when (this) {
        RuleSetAssetFailure.DownloadFailed, null -> R.string.routing_asset_failed_download
        RuleSetAssetFailure.TimedOut -> R.string.routing_asset_failed_timeout
        RuleSetAssetFailure.Rejected -> R.string.routing_asset_failed_rejected
        RuleSetAssetFailure.Unsupplied -> R.string.routing_asset_failed_unsupplied
        RuleSetAssetFailure.InstallFailed -> R.string.routing_asset_failed_install
        RuleSetAssetFailure.Cancelled -> R.string.routing_asset_failed_cancelled
    }

/**
 * One activation-gate marker as a [TonalBanner] on the card. [text] alone is what a screen reader
 * announces: the banner's glyph is decorative. The tone, not the glyph, says how serious it is:
 * [BannerTone.Blocked] gates activation or reports a failed generation, [BannerTone.Neutral] does not.
 */
@Composable
private fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    icon: Int = SubspaceIcons.Warning,
    tone: BannerTone = BannerTone.Neutral,
) {
    TonalBanner(
        icon = icon,
        text = text,
        tone = tone,
        modifier = modifier.padding(top = MARKER_TOP_PADDING),
    )
}
