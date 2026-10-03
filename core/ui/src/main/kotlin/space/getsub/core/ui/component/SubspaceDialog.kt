// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    @DrawableRes icon: Int? = null,
    destructive: Boolean = false,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        SubspaceDialogContent(title, body, confirmLabel, onConfirm, dismissLabel, onDismiss, icon, destructive)
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
    Surface(
        shape = SubspaceShapeTokens.dialog,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(24.dp)) {
            if (icon != null) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(16.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text(confirmLabel) }
                } else {
                    TextButton(onClick = onConfirm) { Text(confirmLabel) }
                }
            }
        }
    }
}
