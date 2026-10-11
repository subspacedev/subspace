// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/**
 * Whether a text field's local draft should be written back: only when it differs from the stored
 * value and [isValid] holds, so leaving a page can never write something the field marks as an
 * error. Shared by every commit path (IME Done and leaving the page) so they cannot disagree.
 */
internal fun shouldCommitDraft(
    draft: String,
    stored: String,
    isValid: Boolean,
): Boolean = isValid && draft != stored

/**
 * Runs [commit] when this leaves composition. Focus loss alone is not enough on a page: Back can
 * dispose the field without delivering it, losing the draft. [commit] reads the latest values.
 */
@Composable
internal fun CommitDraftOnDispose(commit: () -> Unit) {
    val latest = rememberUpdatedState(commit)
    DisposableEffect(Unit) { onDispose { latest.value() } }
}
