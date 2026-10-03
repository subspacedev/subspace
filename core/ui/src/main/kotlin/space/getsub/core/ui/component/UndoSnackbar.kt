// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable

/**
 * Shows "‹message› · Undo" and reports whether Undo was pressed
 * (M8.6 spec §4.2, fix S1). Undo itself is the caller's job.
 */
suspend fun SnackbarHostState.showUndo(
    message: String,
    undoLabel: String,
): Boolean =
    showSnackbar(
        message = message,
        actionLabel = undoLabel,
        withDismissAction = false,
        duration = SnackbarDuration.Short,
    ) == SnackbarResult.ActionPerformed

/** Hosts [showUndo]'s snackbar on the inverse surface. */
@Composable
fun UndoSnackbarHost(state: SnackbarHostState) {
    SnackbarHost(state) { data -> UndoSnackbar(data.visuals.message, data.visuals.actionLabel, data::performAction) }
}

@Composable
internal fun UndoSnackbar(
    message: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Snackbar(
        action = {
            if (actionLabel != null) {
                androidx.compose.material3.TextButton(onClick = onAction) {
                    androidx.compose.material3.Text(actionLabel, color = MaterialTheme.colorScheme.inversePrimary)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) { androidx.compose.material3.Text(message) }
}
