// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the padding/size tokens below — all are
// tokens/spacing.css's --space-* scale, already named by the val each
// initializes. See core/ui's GroupCard.kt and SettingRow.kt for the same
// pattern. TooManyFunctions: the mode selector, the two independent
// warnings, search, the row list and the discard dialog are each their own
// section — the same reasoning RuleSetEditorScreen.kt's own suppression
// gives for its six entry-list sections.
@file:Suppress("MagicNumber", "TooManyFunctions")

package space.getsub.feature.routing

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.getsub.core.model.PerAppMode
import space.getsub.core.ui.component.BannerTone
import space.getsub.core.ui.component.ModeTile
import space.getsub.core.ui.component.ModeTiles
import space.getsub.core.ui.component.OutcomeTag
import space.getsub.core.ui.component.SearchField
import space.getsub.core.ui.component.TonalBanner
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.LocalSubspaceColors
import space.getsub.core.ui.theme.SubspaceShapeTokens
import space.getsub.core.ui.theme.mono

private val CONTENT_PADDING = 20.dp
private val SECTION_GAP = 14.dp
private val ROW_GAP = 12.dp
private val APP_ICON_SIZE = 40.dp
private val TRAY_ICON_SIZE = 48.dp
private val TRAY_CHIP_WIDTH = 60.dp
private val LIST_CORNER = 28.dp
private val LIST_TAIL_CORNER = 10.dp

/**
 * The per-app proxy picker (M5.5 spec §7): mode selector, search and the
 * installed-app list. Reached from Settings, like [RoutingListScreen] — a
 * pushed destination with the floating nav pill hidden, so it carries its own
 * [onBack].
 *
 * Edits are a **draft** ([PerAppViewModel]'s own KDoc) — [onBack] is a real
 * navigation pop, never called while [PerAppState.isDirty] without asking
 * first. Both ways to leave converge on that one gate: the system back
 * gesture via [BackHandler] below, and [PerAppScreenContent]'s own back
 * button via the `onBack` this builds into [PerAppActions] — see the
 * `showDiscardDialog` flag's own comment below for why the confirmation
 * itself lives here rather than split across both.
 *
 * @param onBack pops back to Settings, exactly like [RoutingListScreen]'s own.
 */
@Composable
fun PerAppScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: PerAppViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Owned here, not inside PerAppScreenContent: a back gesture (BackHandler,
    // below) and the on-screen back button both need to raise the *same*
    // confirmation, and PerAppScreenContent's fixed (state, actions, modifier)
    // signature has no channel to report a dialog back up. One flag, one
    // dialog, both paths route through the onBack built into actions below —
    // M5.5 spec §7.3: a back gesture must never silently commit or drop an edit.
    var showDiscardDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = state.isDirty) { showDiscardDialog = true }

    PerAppScreenContent(
        state = state,
        actions =
        PerAppActions(
            onSetMode = viewModel::setMode,
            onToggle = viewModel::toggle,
            onSearch = viewModel::search,
            onClear = viewModel::clear,
            onSave = viewModel::save,
            onDiscard = viewModel::discard,
            onBack = { if (state.isDirty) showDiscardDialog = true else onBack() },
        ),
        modifier = modifier,
    )

    if (showDiscardDialog) {
        DiscardChangesDialog(
            body = stringResource(R.string.per_app_discard_body),
            onConfirm = {
                showDiscardDialog = false
                viewModel.discard()
                onBack()
            },
            onDismiss = { showDiscardDialog = false },
        )
    }
}

/** This screen's callbacks, grouped for the same reason [RoutingListActions] is. */
internal data class PerAppActions(
    val onSetMode: (PerAppMode) -> Unit,
    val onToggle: (String) -> Unit,
    val onSearch: (String) -> Unit,
    val onClear: () -> Unit,
    val onSave: () -> Unit,
    val onDiscard: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * The stateless half — see
 * [space.getsub.feature.home.HomeScreenContent]'s KDoc for why this
 * split exists.
 *
 * Everything below the header scrolls in one [LazyColumn] (this list runs to every installed app,
 * so only visible rows may compose) and the Save bar floats over its bottom, as the rule set
 * editor's does (M8.6 spec §4.7).
 */
@Composable
internal fun PerAppScreenContent(
    state: PerAppState,
    actions: PerAppActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            HeaderRow(onBack = actions.onBack)
            PerAppList(state, actions, Modifier.weight(1f))
        }
        SaveBar(
            enabled = state.isDirty && !state.isEmptyAllowList,
            reconnects = state.isTunnelActive && state.isDirty,
            onSave = actions.onSave,
            modifier = Modifier.align(Alignment.BottomStart).padding(CONTENT_PADDING),
        )
    }
}

private val SAVE_CLEARANCE = 112.dp

@Composable
private fun PerAppList(
    state: PerAppState,
    actions: PerAppActions,
    modifier: Modifier = Modifier,
) {
    val gap = Modifier.padding(bottom = SECTION_GAP)
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = CONTENT_PADDING, end = CONTENT_PADDING, bottom = SAVE_CLEARANCE),
    ) {
        item { ModeSection(state.mode, actions.onSetMode, gap) }
        item { SummaryBanner(state, gap) }
        if (state.isEmptyAllowList) {
            item {
                // The UI half of PerAppState.isEmptyAllowList's guard (M5.5 spec §6.3): Save
                // is disabled below, this is the explanation.
                TonalBanner(
                    icon = SubspaceIcons.Warning,
                    text = stringResource(R.string.per_app_empty_allow_list),
                    tone = BannerTone.Warning,
                    modifier = gap,
                )
            }
        }
        if (state.mode == PerAppMode.Off && state.selectedCount > 0) {
            item {
                Text(
                    text =
                    pluralStringResource(
                        R.plurals.per_app_off_kept,
                        state.selectedCount,
                        state.selectedCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = gap,
                )
            }
        }
        if (state.mode != PerAppMode.Off) {
            if (state.picked.isNotEmpty()) {
                item { PickedTray(state.mode, state.picked, actions.onToggle, actions.onClear, gap) }
            }
            item {
                SearchField(
                    query = state.query,
                    onQueryChange = actions.onSearch,
                    placeholder = stringResource(R.string.per_app_search_hint),
                    modifier = gap,
                )
            }
        }
        item {
            SelectedCountText(
                // The whole selection, never the visible slice of it: typing a
                // query must not appear to untick what the user chose.
                count = state.selectedCount,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        appRows(state, actions.onToggle)
    }
}

@Composable
private fun HeaderRow(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 10.dp, end = CONTENT_PADDING, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(SubspaceIcons.ArrowBack),
                contentDescription = stringResource(R.string.per_app_back),
            )
        }
        Text(
            text = stringResource(R.string.per_app_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 4.dp).semantics { heading() },
        )
    }
}

/** The three [PerAppMode] choices, as the kit's connected tiles. */
@Composable
private fun ModeSection(
    mode: PerAppMode,
    onSetMode: (PerAppMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options =
        PerAppMode.entries.map { entry ->
            val (icon, label, sub) =
                when (entry) {
                    PerAppMode.Off ->
                        Triple(SubspaceIcons.SelectAll, R.string.per_app_mode_off, R.string.per_app_mode_off_sub)
                    PerAppMode.AllowList ->
                        Triple(
                            SubspaceIcons.PlaylistAddCheck,
                            R.string.per_app_mode_allow,
                            R.string.per_app_mode_allow_sub,
                        )
                    PerAppMode.DenyList ->
                        Triple(SubspaceIcons.Block, R.string.per_app_mode_deny, R.string.per_app_mode_deny_sub)
                }
            ModeTile(icon, stringResource(label), stringResource(sub))
        }
    ModeTiles(
        options = options,
        selected = PerAppMode.entries.indexOf(mode),
        onSelect = { onSetMode(PerAppMode.entries[it]) },
        modifier = modifier,
    )
}

/** What the current mode and selection mean, in a sentence (the design's live summary). */
@Composable
private fun SummaryBanner(
    state: PerAppState,
    modifier: Modifier = Modifier,
) {
    val n = state.selectedCount
    val text =
        when {
            state.mode == PerAppMode.Off -> stringResource(R.string.per_app_summary_off)
            state.mode == PerAppMode.AllowList && n == 0 -> stringResource(R.string.per_app_summary_allow_empty)
            state.mode == PerAppMode.DenyList && n == 0 -> stringResource(R.string.per_app_summary_deny_empty)
            state.mode == PerAppMode.AllowList -> pluralStringResource(R.plurals.per_app_summary_allow, n, n)
            else -> pluralStringResource(R.plurals.per_app_summary_deny, n, n)
        }
    TonalBanner(
        icon = if (state.mode == PerAppMode.DenyList) SubspaceIcons.ArrowOutward else SubspaceIcons.Shield,
        text = text,
        modifier = modifier,
    )
}

/** The picked apps, horizontally scrolling, with Clear (fix A8). Tapping a chip unpicks that app. */
@Composable
private fun PickedTray(
    mode: PerAppMode,
    picked: List<AppRow>,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text =
                stringResource(
                    if (mode == PerAppMode.DenyList) R.string.per_app_tray_deny else R.string.per_app_tray_allow,
                    picked.size,
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            TextButton(onClick = onClear) { Text(stringResource(R.string.per_app_clear)) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = 6.dp)) {
            items(picked, key = { it.packageName }) { row -> TrayChip(row, onRemove = { onRemove(row.packageName) }) }
        }
    }
}

@Composable
private fun TrayChip(
    row: AppRow,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val removeLabel = stringResource(R.string.per_app_tray_remove, row.label)
    Column(
        modifier =
        modifier
            .width(TRAY_CHIP_WIDTH)
            .clickable(onClick = onRemove)
            .semantics { contentDescription = removeLabel },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            AppGlyph(row, TRAY_ICON_SIZE)
            Surface(
                shape = SubspaceShapeTokens.pill,
                color = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.align(Alignment.TopEnd).size(22.dp),
            ) {
                Icon(painterResource(SubspaceIcons.Close), contentDescription = null, modifier = Modifier.padding(4.dp))
            }
        }
        Text(
            text = row.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SelectedCountText(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = pluralStringResource(R.plurals.per_app_selected_count, count, count),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * The app rows as one rounded list. Each row is its own lazy item with the card's corners applied
 * to the first and last, so only the visible rows ever compose.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.appRows(
    state: PerAppState,
    onToggle: (String) -> Unit,
) {
    val rows = state.rows
    items(rows, key = { it.packageName }) { row ->
        val first = row == rows.first()
        val last = row == rows.last()
        val shape =
            RoundedCornerShape(
                topStart = if (first) LIST_CORNER else 0.dp,
                topEnd = if (first) LIST_CORNER else 0.dp,
                bottomEnd = if (last) LIST_CORNER else 0.dp,
                bottomStart = if (last) LIST_TAIL_CORNER else 0.dp,
            )
        Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            AppRowItem(row = row, mode = state.mode, onToggle = { onToggle(row.packageName) })
        }
    }
}

/**
 * One app: its icon, label and package name (mono), the [OutcomeTag] saying where its traffic
 * goes, and [AppRow.isInstalled]'s note when false — shown, never hidden (M5.5 spec §7.2).
 * ARCHITECTURE.md §5.6: the package name is rendered, not logged.
 *
 * The package name is skipped as supporting text when it equals [AppRow.label] outright rather
 * than merely being a substring of it: that equality is exactly [PerAppViewModel]'s own
 * `ghostRows` (there is no other label to give a package no longer installed), and rendering the
 * identical string twice would put two nodes with the same text in the tree for no reader's benefit.
 */
@Composable
private fun AppRowItem(
    row: AppRow,
    mode: PerAppMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .toggleable(value = row.isSelected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        AppGlyph(row, APP_ICON_SIZE)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.packageName != row.label) {
                Text(
                    text = row.packageName,
                    style = MaterialTheme.typography.bodySmall.mono(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!row.isInstalled) {
                Text(
                    text = stringResource(R.string.per_app_not_installed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        OutcomeTag(outcomeFor(mode, row.isSelected))
    }
}

/**
 * [row]'s launcher icon, resolved from the platform rather than carried on [AppRow] — see
 * [space.getsub.core.data.InstalledAppsSource]'s own KDoc for why a `Drawable` stays out of the
 * data layer. Where there is none (a package no longer installed, M5.5 spec §7.2's ghost rows, or
 * no PackageManager to ask, as in a screenshot test) a tile with the label's initial stands in.
 *
 * [Image], not `Icon`: an app's launcher icon is full-colour art, not a monochrome glyph, so it
 * must not be tinted the way `Icon` tints its content.
 */
@Composable
private fun AppGlyph(
    row: AppRow,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val packageManager = LocalContext.current.packageManager
    val icon = remember(row.packageName) { packageManager.appIconOrNull(row.packageName) }
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = modifier.size(size))
    } else {
        val s = MaterialTheme.colorScheme
        Surface(
            shape = RoundedCornerShape(size / 3),
            color = if (row.isInstalled) s.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (row.isInstalled) s.onSecondaryContainer else s.onSurfaceVariant,
            border = if (row.isInstalled) null else BorderStroke(2.dp, s.outlineVariant),
            modifier = modifier.size(size),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (row.isInstalled) row.label.take(1).uppercase() else "?",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** `null` for an uninstalled package rather than throwing — the caller falls back to the initial tile. */
private fun PackageManager.appIconOrNull(packageName: String): ImageBitmap? {
    val drawable =
        try {
            getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}

/**
 * The screen's one standout button, in the pop colour (M8.6 spec §2.3). While [reconnects] it
 * carries the note M5.5 spec §7.3 asks for: told before committing, not after the tunnel drops.
 */
@Composable
private fun SaveBar(
    enabled: Boolean,
    reconnects: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pop = LocalSubspaceColors.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onSave,
            enabled = enabled,
            shape = SubspaceShapeTokens.card,
            colors = ButtonDefaults.buttonColors(containerColor = pop.pop, contentColor = pop.onPop),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            modifier = Modifier.heightIn(min = 56.dp),
        ) {
            Icon(painterResource(SubspaceIcons.Check), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text = stringResource(R.string.per_app_save),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (reconnects) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
                Text(
                    text = stringResource(R.string.per_app_reconnect_notice),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
