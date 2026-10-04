// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// The file is named for ServersDialogs, the entry point every other file in
// this package calls — detekt's MatchingDeclarationName instead wants it
// named after ServersDialogActions, the one *class* here, ignoring that the
// file's three functions (ServersDialogs, RenameGroupDialog, DeleteGroupDialog)
// outnumber it. See ServersScreen.kt/ServersGroupList.kt for the same
// entry-point-plus-action-carrier shape.
@file:Suppress("MatchingDeclarationName")

package space.getsub.feature.profiles.list

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import space.getsub.core.ui.component.SubspaceDialog
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.feature.profiles.R

/**
 * [ServersDialogs]' four callbacks, grouped for the same reason
 * [ServersActions] is.
 */
internal data class ServersDialogActions(
    val onRenameConfirm: (Long, String) -> Unit,
    val onRenameDismiss: () -> Unit,
    val onDeleteConfirm: (Long) -> Unit,
    val onDeleteDismiss: () -> Unit,
)

/**
 * Renders the rename and/or delete confirmation for whichever group is
 * currently targeted — `null` renders nothing, the same "closed means absent
 * from composition" convention [space.getsub.core.ui.component.SubspaceBottomSheet]
 * uses for its own `open` flag.
 */
@Composable
internal fun ServersDialogs(
    renameTarget: ServersGroup?,
    deleteTarget: ServersGroup?,
    actions: ServersDialogActions,
) {
    renameTarget?.let { group ->
        RenameGroupDialog(
            group = group,
            onConfirm = { newName -> actions.onRenameConfirm(group.id, newName) },
            onDismiss = actions.onRenameDismiss,
        )
    }

    deleteTarget?.let { group ->
        DeleteGroupDialog(
            group = group,
            onConfirm = { actions.onDeleteConfirm(group.id) },
            onDismiss = actions.onDeleteDismiss,
        )
    }
}

@Composable
private fun RenameGroupDialog(
    group: ServersGroup,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(group.id) { mutableStateOf(group.name) }
    val focus = remember { FocusRequester() }
    SubspaceDialog(
        title = stringResource(R.string.servers_rename_group_title),
        confirmLabel = stringResource(R.string.servers_rename_group_confirm),
        onConfirm = { onConfirm(text) },
        dismissLabel = stringResource(R.string.servers_dialog_cancel),
        onDismiss = onDismiss,
        confirmEnabled = text.isNotBlank(),
    ) {
        // Inside the content lambda: it composes in the dialog's own window, so the requester is
        // attached by the time this effect runs (from the caller's composition it could fire first).
        LaunchedEffect(Unit) { focus.requestFocus() }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

/**
 * Warns before a group is removed — the FK cascades, so this states
 * [ServersGroup.totalProfileCount] rather than leaving the blast radius
 * implicit.
 */
@Composable
private fun DeleteGroupDialog(
    group: ServersGroup,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SubspaceDialog(
        title = stringResource(R.string.servers_delete_group_title, group.name),
        body =
        pluralStringResource(
            R.plurals.servers_delete_group_message,
            group.totalProfileCount,
            group.totalProfileCount,
        ),
        confirmLabel = stringResource(R.string.servers_delete_group_confirm),
        onConfirm = onConfirm,
        dismissLabel = stringResource(R.string.servers_dialog_cancel),
        onDismiss = onDismiss,
        icon = SubspaceIcons.Delete,
        destructive = true,
    )
}
