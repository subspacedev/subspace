// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.theme.SubspaceShapeTokens

/**
 * A confirmation dialog with the tight-corner dialog shape (M8.6 spec §4.2).
 * When [destructive] the confirm button is filled with the error colours (the
 * design's Discard); otherwise it is a text button like the dismiss action.
 */
@Suppress("LongParameterList")
@Composable
fun SubspaceDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    destructive: Boolean = false,
) {
    SubspaceDialog(
        title = title,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        dismissLabel = dismissLabel,
        onDismiss = onDismiss,
        modifier = modifier,
        icon = icon,
        destructive = destructive,
    ) {
        DialogBody(body)
    }
}

/**
 * The same dialog with a content slot in place of the body text, for a dialog that asks for
 * something (the Servers rename field). [confirmEnabled] gates the confirm button only; the
 * dismiss action is always live.
 */
@Suppress("LongParameterList")
@Composable
fun SubspaceDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss, modifier = modifier) {
        SubspaceDialogSurface(
            title, confirmLabel, onConfirm, dismissLabel, onDismiss, icon, destructive, confirmEnabled, content,
        )
    }
}

/** The dialog's surface, separate from the window so snapshots can render it. */
@Suppress("LongParameterList")
@Composable
internal fun SubspaceDialogContent(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
    @DrawableRes icon: Int?,
    destructive: Boolean,
) {
    SubspaceDialogSurface(title, confirmLabel, onConfirm, dismissLabel, onDismiss, icon, destructive, true) {
        DialogBody(body)
    }
}

/** The slot version of [SubspaceDialogContent]; both overloads of [SubspaceDialog] end up here. */
@Suppress("LongParameterList")
@Composable
internal fun SubspaceDialogSurface(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
    @DrawableRes icon: Int?,
    destructive: Boolean,
    confirmEnabled: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = SubspaceShapeTokens.dialog,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(24.dp)) {
            if (icon != null) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(16.dp))
            content()
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
                if (destructive) {
                    Button(
                        onClick = onConfirm,
                        enabled = confirmEnabled,
                        colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text(confirmLabel) }
                } else {
                    TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) }
                }
            }
        }
    }
}

@Composable
private fun DialogBody(body: String) {
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
