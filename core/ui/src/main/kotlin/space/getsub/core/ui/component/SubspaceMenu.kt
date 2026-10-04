// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MatchingDeclarationName") // MenuItem is the menu's item type; the file is named for the composable.

package space.getsub.core.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

/** One row of a [SubspaceMenu]; [destructive] rows read in the error colour. */
data class MenuItem(
    @DrawableRes val icon: Int,
    val label: String,
    val destructive: Boolean = false,
    /** Announced as selected (a current choice), for menus whose icon column marks one. */
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/** A dropdown menu of icon + label rows (M8.6 spec §4.2). Choosing a row also dismisses. */
@Composable
fun SubspaceMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<MenuItem>,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) { MenuRows(items, onDismiss) }
}

@Composable
internal fun ColumnScope.MenuRows(
    items: List<MenuItem>,
    onDismiss: () -> Unit,
) {
    items.forEach { item ->
        val tint = if (item.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        DropdownMenuItem(
            text = { Text(item.label, color = tint) },
            modifier = Modifier.semantics { selected = item.selected },
            leadingIcon = { Icon(painterResource(item.icon), contentDescription = null, tint = tint) },
            onClick = {
                onDismiss()
                item.onClick()
            },
        )
    }
}
