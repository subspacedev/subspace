// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the padding/size tokens below — all are
// the design's spacing, already named by the val each initializes. See core/ui's
// GroupCard.kt and HubRow.kt for the same pattern. TooManyFunctions: this
// file's function count is the width of the form it renders — name, order,
// domain strategy, three bucket cards of two fields each, the save bar — the
// same reasoning EditorScreen.kt's own suppression gives.
@file:Suppress("MagicNumber", "TooManyFunctions")

package space.getsub.feature.routing

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.getsub.core.model.BucketField
import space.getsub.core.model.DomainStrategy
import space.getsub.core.model.EntryProblem
import space.getsub.core.model.RouteOutcome
import space.getsub.core.model.RoutingEntries
import space.getsub.core.ui.component.BannerTone
import space.getsub.core.ui.component.ModeTile
import space.getsub.core.ui.component.ModeTiles
import space.getsub.core.ui.component.TonalBanner
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.LocalSubspaceColors
import space.getsub.core.ui.theme.SubspaceShapeTokens
import space.getsub.core.ui.theme.mono
import space.getsub.core.ui.theme.tabular
import java.text.NumberFormat

private val CONTENT_PADDING = 20.dp
private val SECTION_GAP = 20.dp
private val LABEL_GAP = 8.dp
private val LOADING_TOP_PADDING = 48.dp

// Room under the last section for the save bar, which floats over the form: the bar's 56 dp, its
// 20 dp margin and a 28 dp breath, so the last Add button rests clear of it at full scroll. The
// system bars and the keyboard are not part of this number: the whole editor is padded for them.
private val SAVE_BAR_CLEARANCE = 104.dp

/**
 * Identifies the loading spinner to `RuleSetEditorScreenContentTest` — same pattern as
 * [EditorScreen][space.getsub.feature.profiles.editor.EditorScreen]'s own
 * `EDITOR_LOADING_TEST_TAG`.
 */
internal const val RULE_SET_EDITOR_LOADING_TEST_TAG = "rule-set-editor-loading"

/** Identifies one bucket field's entry input to tests; one per [outcome]/[field] once expanded. */
internal fun ruleSetEntryFieldTag(
    outcome: RouteOutcome,
    field: BucketField,
): String = "rule-set-entry-${outcome.name}-${field.name}"

/**
 * `:app`'s `RuleSetEditor(ruleSetId)` route — one rule set's editor (M8.6 spec §4.6). See
 * [RuleSetEditorViewModel]'s own KDoc for the working-copy/write-through-on-save shape.
 *
 * Leaving with unsaved edits asks first, by system Back or by the top-bar arrow (fix A11) — see
 * [DiscardGuard]. A successful save leaves through [onDone] directly, never through that gate:
 * the same state update that says `saved` also clears `isDirty`
 * ([RuleSetEditorViewModel.save]), so there is no window in which Back could ask about a
 * finished save.
 */
@Composable
fun RuleSetEditorScreen(
    ruleSetId: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: RuleSetEditorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(ruleSetId) { viewModel.load(ruleSetId) }
    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    DiscardGuard(
        isDirty = state.isDirty,
        body = stringResource(R.string.rule_set_editor_discard_body),
        onLeave = onDone,
    ) { requestLeave ->
        RuleSetEditorContent(
            state = state,
            actions =
            RuleSetEditorActions(
                onBack = requestLeave,
                onNameChanged = viewModel::setName,
                onDraftChanged = viewModel::setDraft,
                onAddEntry = viewModel::addEntry,
                onRemoveEntry = viewModel::removeEntry,
                onMoveEarlier = { outcome -> viewModel.setOrder(state.order.moved(outcome, -1)) },
                onMoveLater = { outcome -> viewModel.setOrder(state.order.moved(outcome, 1)) },
                onDomainStrategyChanged = viewModel::setDomainStrategy,
                onSave = viewModel::save,
                onRevealHandled = viewModel::revealHandled,
            ),
            modifier = modifier,
        )
    }
}

/** Swaps the element at [outcome] with its neighbour [delta] positions away. A no-op at either end. */
private fun List<RouteOutcome>.moved(
    outcome: RouteOutcome,
    delta: Int,
): List<RouteOutcome> {
    val index = indexOf(outcome)
    val target = index + delta
    if (index < 0 || target !in indices) return this
    return toMutableList().apply {
        this[index] = this[target]
        this[target] = outcome
    }
}

/**
 * This screen's callbacks, grouped for the same reason
 * [EditorActions][space.getsub.feature.profiles.editor.EditorActions] is.
 */
internal data class RuleSetEditorActions(
    val onBack: () -> Unit,
    val onNameChanged: (String) -> Unit,
    val onDraftChanged: (RouteOutcome, BucketField, String) -> Unit,
    val onAddEntry: (RouteOutcome, BucketField, String) -> Unit,
    val onRemoveEntry: (RouteOutcome, BucketField, String) -> Unit,
    val onMoveEarlier: (RouteOutcome) -> Unit,
    val onMoveLater: (RouteOutcome) -> Unit,
    val onDomainStrategyChanged: (DomainStrategy) -> Unit,
    val onSave: () -> Unit,
    val onRevealHandled: () -> Unit = {},
)

/**
 * The stateless half — see
 * [space.getsub.feature.home.HomeScreenContent]'s KDoc for why this
 * split exists.
 */
@OptIn(ExperimentalLayoutApi::class) // isImeVisible; not the Expressive opt-in.
@Composable
internal fun RuleSetEditorContent(
    state: RuleSetEditorState,
    actions: RuleSetEditorActions,
    modifier: Modifier = Modifier,
) {
    // The activity draws edge to edge and nothing above pads for the bottom of the display, so this
    // screen does: the navigation bar, or the keyboard when it is up (the larger of the two). With
    // the keyboard up the form's viewport shrinks to what is left, which is what lets a field and
    // its inline problem be scrolled above it. Save is hidden meanwhile: riding up with the keyboard
    // it sat on top of the very field being typed in.
    val imeVisible = WindowInsets.isImeVisible
    Box(
        modifier =
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            EditorTopBar(
                title =
                when {
                    state.loading -> ""
                    state.id == 0L -> stringResource(R.string.rule_set_editor_title_new)
                    else -> stringResource(R.string.rule_set_editor_title_edit)
                },
                onBack = actions.onBack,
            )
            if (state.loading) {
                LoadingBody()
            } else {
                EditorForm(state = state, actions = actions)
            }
        }
        if (!state.loading && !imeVisible) {
            SaveBar(
                enabled = state.canSave && !state.saving,
                saving = state.saving,
                onSave = actions.onSave,
                modifier = Modifier.align(Alignment.BottomStart).padding(CONTENT_PADDING),
            )
        }
    }
}

@Composable
private fun EditorTopBar(
    title: String,
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
                contentDescription = stringResource(R.string.rule_set_editor_back_description),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp).semantics { heading() },
        )
    }
}

@Composable
private fun LoadingBody(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // No `contentDescription = ""`: RULE_SET_EDITOR_LOADING_TEST_TAG is how a test finds this
        // node, and an empty description would only make TalkBack stop here and say nothing.
        CircularProgressIndicator(
            modifier =
            Modifier
                .padding(top = LOADING_TOP_PADDING)
                .testTag(RULE_SET_EDITOR_LOADING_TEST_TAG),
        )
    }
}

@Composable
private fun EditorForm(
    state: RuleSetEditorState,
    actions: RuleSetEditorActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CONTENT_PADDING)
            .padding(top = 12.dp, bottom = SAVE_BAR_CLEARANCE),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        // Nothing is editable while the write is in flight: it was built from the state before it,
        // so an edit made now would be lost when the screen leaves (fix round 1, minor 7).
        // RuleSetEditorViewModel refuses such edits too; this only stops offering them.
        val enabled = !state.saving

        // M9-E spec §2 "warn but obey": the tunnel runs as chosen, this only says what it costs.
        if (state.showSniffingWarning) {
            TonalBanner(
                icon = SubspaceIcons.Warning,
                text = stringResource(R.string.rule_set_editor_sniffing_off_warning),
                tone = BannerTone.Warning,
            )
        }

        NameSection(name = state.name, onNameChanged = actions.onNameChanged, enabled = enabled)

        OrderSection(
            order = state.order,
            onMoveEarlier = actions.onMoveEarlier,
            onMoveLater = actions.onMoveLater,
            enabled = enabled,
        )

        DomainStrategySection(
            selected = state.domainStrategy,
            onSelected = actions.onDomainStrategyChanged,
            enabled = enabled,
        )

        state.order.forEach { outcome ->
            OutcomeCard(outcome = outcome, state = state, actions = actions, enabled = enabled)
        }

        // Save-time-only problems (fix round 1, Findings 7/8) — a rejected-entry-while-typing
        // problem is shown inline on its own field, never here (Finding 4).
        if (state.saveProblem != null) {
            Text(
                text = state.saveProblem.toDisplayText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** A section's label: `titleSmall` in primary, as the design's "Name", "Order", "Domain strategy". */
@Composable
private fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun NameSection(
    name: String,
    onNameChanged: (String) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(R.string.rule_set_editor_name_label))
        TextField(
            value = name,
            onValueChange = onNameChanged,
            enabled = enabled,
            placeholder = { Text(stringResource(R.string.rule_set_editor_name_placeholder)) },
            singleLine = true,
            shape = SubspaceShapeTokens.bubble,
            colors =
            TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The three [RouteOutcome]s in evaluation order, each movable earlier/later within the list. */
@Composable
private fun OrderSection(
    order: List<RouteOutcome>,
    onMoveEarlier: (RouteOutcome) -> Unit,
    onMoveLater: (RouteOutcome) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LABEL_GAP)) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            SectionLabel(stringResource(R.string.rule_set_editor_order_label), modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.rule_set_editor_order_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = SubspaceShapeTokens.card,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
                order.forEachIndexed { index, outcome ->
                    OrderRow(
                        position = index + 1,
                        outcome = outcome,
                        canMoveEarlier = enabled && index > 0,
                        canMoveLater = enabled && index < order.lastIndex,
                        onMoveEarlier = { onMoveEarlier(outcome) },
                        onMoveLater = { onMoveLater(outcome) },
                    )
                }
            }
        }
    }
}

// The row's width: a position, an outcome, and its two moves. Grouping them into a carrier class
// would only move the same names one level down — same reasoning as EntryField's suppression.
@Suppress("LongParameterList")
@Composable
private fun OrderRow(
    position: Int,
    outcome: RouteOutcome,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
) {
    val name = outcome.displayName()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Text(
            text = position.toString(),
            style = MaterialTheme.typography.labelLarge.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(14.dp),
        )
        OutcomeTile(outcome = outcome, size = 36, shape = MaterialTheme.shapes.medium)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = outcome.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMoveEarlier, enabled = canMoveEarlier) {
            Icon(
                painter = painterResource(SubspaceIcons.KeyboardArrowUp),
                contentDescription = stringResource(R.string.rule_set_editor_move_earlier_description, name),
            )
        }
        IconButton(onClick = onMoveLater, enabled = canMoveLater) {
            Icon(
                painter = painterResource(SubspaceIcons.KeyboardArrowDown),
                contentDescription = stringResource(R.string.rule_set_editor_move_later_description, name),
            )
        }
    }
}

/** The outcome's icon on its own colour — the same tile in the order list and on its bucket card. */
@Composable
private fun OutcomeTile(
    outcome: RouteOutcome,
    size: Int,
    shape: Shape,
) {
    val colors = outcome.colors()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size.dp).background(colors.tile, shape),
    ) {
        Icon(
            painter = painterResource(outcome.icon()),
            contentDescription = null,
            tint = colors.onTile,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun DomainStrategySection(
    selected: DomainStrategy,
    onSelected: (DomainStrategy) -> Unit,
    enabled: Boolean,
) {
    val strategies = DomainStrategy.entries
    Column(verticalArrangement = Arrangement.spacedBy(LABEL_GAP)) {
        SectionLabel(stringResource(R.string.rule_set_editor_domain_strategy_label))
        ModeTiles(
            options = strategies.map { ModeTile(icon = it.icon(), label = it.displayName()) },
            selected = strategies.indexOf(selected),
            onSelect = { index -> onSelected(strategies[index]) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = selected.help(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One outcome's bucket: a tonal header naming it, then its Sites and IPs fields. */
@Composable
private fun OutcomeCard(
    outcome: RouteOutcome,
    state: RuleSetEditorState,
    actions: RuleSetEditorActions,
    enabled: Boolean,
) {
    val total = BucketField.entries.sumOf { state.bucket(outcome, it).size }
    Surface(
        shape = SubspaceShapeTokens.cardLead,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            OutcomeHeader(outcome = outcome, total = total)
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
            ) {
                BucketField.entries.forEach { field ->
                    EntryField(
                        outcome = outcome,
                        field = field,
                        entries = state.bucket(outcome, field),
                        draft = state.draft(outcome, field),
                        reveal = state.revealRequest?.takeIf { it.outcome == outcome && it.field == field },
                        categories = state.categoriesFor(field),
                        actions = actions,
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

/** A bucket card's tonal header: the outcome's tile, name, description and entry count. */
@Composable
private fun OutcomeHeader(
    outcome: RouteOutcome,
    total: Int,
) {
    val colors = outcome.colors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
        Modifier
            .fillMaxWidth()
            .background(colors.header)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        OutcomeTile(outcome = outcome, size = 40, shape = SubspaceShapeTokens.bubble)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = outcome.displayName(),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onHeader,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = outcome.description(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onHeader,
            )
        }
        Text(
            text =
            if (total == 0) {
                stringResource(R.string.rule_set_editor_bucket_empty)
            } else {
                pluralStringResource(R.plurals.routing_entry_count, total, total)
            },
            style = MaterialTheme.typography.labelLarge.tabular(),
            color = colors.onHeader,
        )
    }
}

/**
 * One of the six bucket fields: its stored entries as chips, an input with the design's inline
 * checks, and — only when [categories] is non-empty — Browse. An empty field collapses to one
 * "Add sites" / "Add IPs" button until it is opened or has text (M8.6 spec §4.9, fix S5).
 * ARCHITECTURE.md §5.6: every entry below is exactly what the user themself typed or picked;
 * nothing here is logged.
 */
// The field's width: which bucket, what it holds, what is typed, what can be browsed, and the
// callbacks — not a candidate for a carrier class that would move the same names one level down.
@Suppress("LongParameterList")
@Composable
private fun EntryField(
    outcome: RouteOutcome,
    field: BucketField,
    entries: List<String>,
    draft: String,
    reveal: RevealRequest?,
    categories: List<GeoCategory>,
    actions: RuleSetEditorActions,
    enabled: Boolean,
) {
    var opened by rememberSaveable(outcome, field) { mutableStateOf(false) }
    var focusOnOpen by remember { mutableStateOf(false) }
    val showInput = entries.isNotEmpty() || draft.isNotEmpty() || opened
    val onPick: (String) -> Unit = { entry -> actions.onAddEntry(outcome, field, entry) }

    Column(verticalArrangement = Arrangement.spacedBy(LABEL_GAP)) {
        Text(
            text = field.displayName(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!showInput) {
            CollapsedField(
                field = field,
                categories = categories,
                enabled = enabled,
                onOpen = {
                    opened = true
                    focusOnOpen = true
                },
                onPick = onPick,
            )
        } else {
            if (entries.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    entries.forEach { entry ->
                        EntryChip(
                            entry = entry,
                            onRemove = { actions.onRemoveEntry(outcome, field, entry) },
                            enabled = enabled,
                        )
                    }
                }
            }
            EntryInput(
                outcome = outcome,
                field = field,
                draft = draft,
                reveal = reveal,
                categories = categories,
                focusOnShow = focusOnOpen,
                onFocused = { focusOnOpen = false },
                actions = actions,
                enabled = enabled,
            )
        }
    }
}

/** An empty, unopened field (fix S5): its Add button, and Browse when there are categories. */
@Composable
private fun CollapsedField(
    field: BucketField,
    categories: List<GeoCategory>,
    enabled: Boolean,
    onOpen: () -> Unit,
    onPick: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextButton(onClick = onOpen, enabled = enabled) {
            Icon(painterResource(SubspaceIcons.Add), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(field.addLabel(), modifier = Modifier.padding(start = 8.dp))
        }
        if (categories.isNotEmpty()) {
            Browse(
                prefix = RoutingEntries.builtInPrefix(field),
                categories = categories,
                onSelected = onPick,
                enabled = enabled,
            )
        }
    }
}

/** The input row and its inline problem. Shows the first problem found (M8.6 Part 4 plan ruling R4). */
@Suppress("LongParameterList") // Same reasoning as EntryField's.
@Composable
private fun EntryInput(
    outcome: RouteOutcome,
    field: BucketField,
    draft: String,
    reveal: RevealRequest?,
    categories: List<GeoCategory>,
    focusOnShow: Boolean,
    onFocused: () -> Unit,
    actions: RuleSetEditorActions,
    enabled: Boolean,
) {
    // Blank is not shown: an empty field is not yet a mistake.
    val rejection = if (draft.isEmpty()) null else rejectionOf(draft, field)
    val canAdd = enabled && draft.isNotBlank() && rejection == null
    val problem = rejection?.toDisplayText()
    val add = { if (canAdd) actions.onAddEntry(outcome, field, draft) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusOnShow) {
        if (focusOnShow) {
            focusRequester.requestFocus()
            onFocused()
        }
    }

    val bringIntoView = remember { BringIntoViewRequester() }
    // A Save that was refused because of this field's text: focus it and scroll it, with its
    // inline problem, into view. The token is what re-fires this on a second refused Save.
    RevealEffect(reveal, focusRequester, bringIntoView, actions.onRevealHandled)

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.bringProblemIntoView(problem, bringIntoView),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            EntryTextField(
                draft = draft,
                field = field,
                problem = problem,
                enabled = enabled,
                canAdd = canAdd,
                onDraftChanged = { actions.onDraftChanged(outcome, field, it) },
                onAdd = add,
                modifier =
                Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .testTag(ruleSetEntryFieldTag(outcome, field)),
            )
            if (categories.isNotEmpty()) {
                Browse(
                    prefix = RoutingEntries.builtInPrefix(field),
                    categories = categories,
                    onSelected = { entry -> actions.onAddEntry(outcome, field, entry) },
                    enabled = enabled,
                )
            }
        }
        if (problem != null) {
            Text(
                text = problem,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Shows this field when a Save was refused over its text: focus it, scroll it into view, and
 * report the request handled. [reveal] is non-null only for the field it names.
 */
@Composable
private fun RevealEffect(
    reveal: RevealRequest?,
    focusRequester: FocusRequester,
    bringIntoView: BringIntoViewRequester,
    onHandled: () -> Unit,
) {
    LaunchedEffect(reveal) {
        if (reveal != null) {
            focusRequester.requestFocus()
            bringIntoView.bringIntoView()
            onHandled()
        }
    }
}

/**
 * Keeps a field and its inline problem on screen together. The message is the last thing in the
 * column and used to sit below the keyboard while the field above it stayed visible, so the whole
 * column is what gets brought into view, when a problem appears for the focused field.
 */
@Composable
private fun Modifier.bringProblemIntoView(
    problem: String?,
    requester: BringIntoViewRequester,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(problem, focused) {
        if (problem != null && focused) requester.bringIntoView()
    }
    return this.bringIntoViewRequester(requester).onFocusChanged { focused = it.hasFocus }
}

/** The entry input itself, mono (a machine string), with its add button inside it as the design has. */
@Suppress("LongParameterList") // Same reasoning as EntryField's.
@Composable
private fun EntryTextField(
    draft: String,
    field: BucketField,
    problem: String?,
    enabled: Boolean,
    canAdd: Boolean,
    onDraftChanged: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = draft,
        onValueChange = onDraftChanged,
        enabled = enabled,
        placeholder = {
            Text(
                text = field.hint(),
                style = MaterialTheme.typography.bodySmall.mono(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        textStyle = MaterialTheme.typography.bodySmall.mono(),
        singleLine = true,
        isError = problem != null,
        shape = MaterialTheme.shapes.large,
        colors =
        OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            errorContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        keyboardOptions =
        KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onAdd() }),
        trailingIcon = {
            FilledIconButton(
                onClick = onAdd,
                enabled = canAdd,
                shape = MaterialTheme.shapes.medium,
                colors =
                IconButtonDefaults.filledIconButtonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    painter = painterResource(SubspaceIcons.Add),
                    contentDescription = stringResource(R.string.rule_set_editor_add_description),
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        // The reason shown under the field, attached to the field itself so TalkBack says why it
        // is in error when it lands there, not only when it reaches the text below (fix round 1,
        // minor 8).
        modifier = if (problem != null) modifier.semantics { error(problem) } else modifier,
    )
}

/** One stored entry, in mono (a machine string), with its remove control. */
@Composable
private fun EntryChip(
    entry: String,
    onRemove: () -> Unit,
    enabled: Boolean,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 32.dp).padding(start = 12.dp, end = 2.dp),
        ) {
            Text(
                text = entry,
                style = MaterialTheme.typography.bodySmall.mono(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(32.dp)) {
                Icon(
                    painter = painterResource(SubspaceIcons.Close),
                    contentDescription = stringResource(R.string.rule_set_editor_remove_entry_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * "Browse" for one field: the geo categories with their rule counts. [onSelected] receives
 * [prefix] + the picked [GeoCategory.code] — the same string [RuleSetEditorViewModel.addEntry]
 * validates for any other entry, so a picked category goes through the exact same checks as one
 * typed by hand.
 */
@Composable
private fun Browse(
    prefix: String,
    categories: List<GeoCategory>,
    onSelected: (String) -> Unit,
    enabled: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Box {
        OutlinedButton(
            onClick = { menuOpen = true },
            enabled = enabled,
            shape = MaterialTheme.shapes.large,
            contentPadding = PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            // The entry input's own height, so the two sit level on one row.
            modifier = Modifier.height(56.dp),
        ) {
            Icon(painterResource(SubspaceIcons.Category), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.rule_set_editor_browse), modifier = Modifier.padding(start = 4.dp))
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            categories.forEach { category ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = prefix + category.code,
                                style = MaterialTheme.typography.bodyMedium.mono(),
                                modifier = Modifier.weight(1f).padding(end = 12.dp),
                            )
                            Text(
                                text =
                                pluralStringResource(
                                    R.plurals.rule_set_editor_category_rules,
                                    category.ruleCount,
                                    numbers.format(category.ruleCount),
                                ),
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        menuOpen = false
                        onSelected(prefix + category.code)
                    },
                )
            }
        }
    }
}

/** The screen's one standout button, in the pop colour (M8.6 spec §2.3). */
@Composable
private fun SaveBar(
    enabled: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pop = LocalSubspaceColors.current
    Button(
        onClick = onSave,
        enabled = enabled,
        shape = SubspaceShapeTokens.card,
        colors = ButtonDefaults.buttonColors(containerColor = pop.pop, contentColor = pop.onPop),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = modifier.height(56.dp),
    ) {
        Icon(
            painter = painterResource(if (saving) SubspaceIcons.ProgressActivity else SubspaceIcons.Check),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text =
            stringResource(if (saving) R.string.rule_set_editor_saving else R.string.rule_set_editor_save_button),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** An outcome's header and tile colours: Proxy is the connected (tertiary) role, Direct the secondary. */
private data class OutcomeColors(
    val header: Color,
    val onHeader: Color,
    val tile: Color,
    val onTile: Color,
)

@Composable
private fun RouteOutcome.colors(): OutcomeColors {
    val s = MaterialTheme.colorScheme
    return when (this) {
        RouteOutcome.BLOCK -> OutcomeColors(s.surfaceContainerHighest, s.onSurface, s.error, s.onError)
        RouteOutcome.PROXY -> OutcomeColors(s.tertiaryContainer, s.onTertiaryContainer, s.tertiary, s.onTertiary)
        RouteOutcome.DIRECT -> OutcomeColors(s.secondaryContainer, s.onSecondaryContainer, s.secondary, s.onSecondary)
    }
}

@DrawableRes
private fun RouteOutcome.icon(): Int =
    when (this) {
        RouteOutcome.BLOCK -> SubspaceIcons.Block
        RouteOutcome.PROXY -> SubspaceIcons.Shield
        RouteOutcome.DIRECT -> SubspaceIcons.ArrowOutward
    }

@Composable
private fun RouteOutcome.displayName(): String =
    when (this) {
        RouteOutcome.BLOCK -> stringResource(R.string.rule_set_editor_outcome_block)
        RouteOutcome.PROXY -> stringResource(R.string.rule_set_editor_outcome_proxy)
        RouteOutcome.DIRECT -> stringResource(R.string.rule_set_editor_outcome_direct)
    }

@Composable
private fun RouteOutcome.description(): String =
    when (this) {
        RouteOutcome.BLOCK -> stringResource(R.string.rule_set_editor_outcome_block_description)
        RouteOutcome.PROXY -> stringResource(R.string.rule_set_editor_outcome_proxy_description)
        RouteOutcome.DIRECT -> stringResource(R.string.rule_set_editor_outcome_direct_description)
    }

@Composable
private fun BucketField.displayName(): String =
    when (this) {
        BucketField.SITES -> stringResource(R.string.rule_set_editor_field_sites)
        BucketField.IPS -> stringResource(R.string.rule_set_editor_field_ips)
    }

@Composable
private fun BucketField.addLabel(): String =
    when (this) {
        BucketField.SITES -> stringResource(R.string.rule_set_editor_add_sites)
        BucketField.IPS -> stringResource(R.string.rule_set_editor_add_ips)
    }

@Composable
private fun BucketField.hint(): String =
    when (this) {
        BucketField.SITES -> stringResource(R.string.rule_set_editor_entry_hint_sites)
        BucketField.IPS -> stringResource(R.string.rule_set_editor_entry_hint_ips)
    }

@DrawableRes
private fun DomainStrategy.icon(): Int =
    when (this) {
        DomainStrategy.AS_IS -> SubspaceIcons.Speed
        DomainStrategy.IP_IF_NON_MATCH -> SubspaceIcons.AltRoute
        DomainStrategy.IP_ON_DEMAND -> SubspaceIcons.Dns
    }

@Composable
private fun DomainStrategy.displayName(): String =
    when (this) {
        DomainStrategy.AS_IS -> stringResource(R.string.rule_set_editor_domain_strategy_as_is)
        DomainStrategy.IP_IF_NON_MATCH -> stringResource(R.string.rule_set_editor_domain_strategy_ip_if_non_match)
        DomainStrategy.IP_ON_DEMAND -> stringResource(R.string.rule_set_editor_domain_strategy_ip_on_demand)
    }

@Composable
private fun DomainStrategy.help(): String =
    when (this) {
        DomainStrategy.AS_IS -> stringResource(R.string.rule_set_editor_domain_strategy_as_is_help)
        DomainStrategy.IP_IF_NON_MATCH -> stringResource(R.string.rule_set_editor_domain_strategy_ip_if_non_match_help)
        DomainStrategy.IP_ON_DEMAND -> stringResource(R.string.rule_set_editor_domain_strategy_ip_on_demand_help)
    }

/** Neither vocabulary carries the entry (ARCHITECTURE.md §5.6), so no message here repeats it. */
@Composable
private fun EntryRejection.toDisplayText(): String =
    when (this) {
        is EntryRejection.Hint -> hint.toDisplayText()
        is EntryRejection.Problem -> problem.toDisplayText()
    }

@Composable
private fun EntryHint.toDisplayText(): String =
    when (this) {
        EntryHint.SpacesOrCommas -> stringResource(R.string.rule_set_editor_hint_spaces_or_commas)
        EntryHint.MissingCategory -> stringResource(R.string.rule_set_editor_hint_missing_category)
        EntryHint.UseGeositeForSites -> stringResource(R.string.rule_set_editor_hint_use_geosite)
        EntryHint.UseGeoipForIps -> stringResource(R.string.rule_set_editor_hint_use_geoip)
        EntryHint.MalformedExt -> stringResource(R.string.rule_set_editor_hint_malformed_ext)
        EntryHint.NotADomain -> stringResource(R.string.rule_set_editor_hint_not_a_domain)
        EntryHint.NotAnIp -> stringResource(R.string.rule_set_editor_hint_not_an_ip)
    }

@Composable
private fun EntryProblem.toDisplayText(): String =
    when (this) {
        EntryProblem.Blank -> stringResource(R.string.rule_set_editor_problem_blank)
        EntryProblem.MissingGeoCode -> stringResource(R.string.rule_set_editor_problem_missing_geo_code)
        EntryProblem.MalformedExtReference -> stringResource(R.string.rule_set_editor_problem_malformed_ext_reference)
        EntryProblem.MalformedAddress -> stringResource(R.string.rule_set_editor_problem_malformed_address)
        EntryProblem.MalformedDomain -> stringResource(R.string.rule_set_editor_problem_malformed_domain)
        EntryProblem.IllegalCharacter -> stringResource(R.string.rule_set_editor_problem_illegal_character)
    }

/**
 * [SaveProblem.NameConflict.name] is exempt from ARCHITECTURE.md §5.6 the same way
 * [SaveProblem.NameConflict]'s own KDoc says — it is the conflicting rule
 * set's name, not an entry.
 */
@Composable
private fun SaveProblem.toDisplayText(): String =
    when (this) {
        SaveProblem.InvalidEntry -> stringResource(R.string.rule_set_editor_save_problem_invalid_entry)
        SaveProblem.WriteFailed -> stringResource(R.string.rule_set_editor_save_problem_write_failed)
        is SaveProblem.NameConflict -> stringResource(R.string.rule_set_editor_save_problem_name_conflict, name)
    }
