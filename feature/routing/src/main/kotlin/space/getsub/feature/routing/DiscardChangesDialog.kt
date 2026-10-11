// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import space.getsub.core.ui.component.SubspaceDialog

/**
 * Confirms losing a draft before a screen pops (M8.6 spec §4.8): Per-app, and the rule set
 * editor (fix A11). Discard is the destructive action, so it is the filled error button.
 *
 * `internal`, not `private`: the screens that raise it need a Hilt ViewModel to render, so this
 * one composable is tested on its own — content and callback wiring. See `DiscardChangesDialogTest`.
 *
 * @param body what would be lost, in the raising screen's own words.
 */
@Composable
internal fun DiscardChangesDialog(
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SubspaceDialog(
        title = stringResource(R.string.discard_changes_title),
        body = body,
        confirmLabel = stringResource(R.string.discard_changes_confirm),
        onConfirm = onConfirm,
        dismissLabel = stringResource(R.string.discard_changes_cancel),
        onDismiss = onDismiss,
        destructive = true,
    )
}

/**
 * The rule set editor's leave gate (M8.6 spec §4.9, fix A11). Both ways out — system Back and the
 * top-bar arrow, which [content] wires to the `requestLeave` it is handed — ask first while
 * [isDirty], and leave silently through [onLeave] when not. With nothing to lose, system Back is
 * not intercepted at all and the navigation host pops as usual.
 *
 * Split from [RuleSetEditorScreen] so `RuleSetEditorDiscardGuardTest` can drive a real back
 * press against it without the Hilt ViewModel that screen looks up.
 */
@Composable
internal fun DiscardGuard(
    isDirty: Boolean,
    body: String,
    onLeave: () -> Unit,
    content: @Composable (requestLeave: () -> Unit) -> Unit,
) {
    // Plain remember, not saveable: the edits it guards are not saved, so after process death a
    // restored dialog would sit over a clean form.
    var asking by remember { mutableStateOf(false) }

    BackHandler(enabled = isDirty) { asking = true }

    content { if (isDirty) asking = true else onLeave() }

    if (asking) {
        DiscardChangesDialog(
            body = body,
            onConfirm = {
                asking = false
                onLeave()
            },
            onDismiss = { asking = false },
        )
    }
}
