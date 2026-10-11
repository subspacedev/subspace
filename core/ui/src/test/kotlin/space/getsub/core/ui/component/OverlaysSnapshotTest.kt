// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.snapshot.snapshotThemes

class OverlaysSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun discardDialog() =
        paparazzi.snapshotThemes("dialog-discard") {
            SubspaceDialogContent(
                title = "Discard changes?",
                body = "Your edits to this rule set will be lost.",
                confirmLabel = "Discard",
                onConfirm = {},
                dismissLabel = "Keep editing",
                onDismiss = {},
                icon = null,
                destructive = true,
            )
        }

    @Test
    fun batteryDialog() =
        paparazzi.snapshotThemes("dialog-battery") {
            SubspaceDialogContent(
                title = "Keep the tunnel alive?",
                body =
                "Android may stop Subspace to save battery, which drops the tunnel. " +
                    "Exempting it keeps startup connect and the kill switch dependable. We'll only ask once.",
                confirmLabel = "Open settings",
                onConfirm = {},
                dismissLabel = "Not now",
                onDismiss = {},
                icon = SubspaceIcons.BatterySaver,
                destructive = false,
            )
        }

    @Test
    fun inputDialog() =
        paparazzi.snapshotThemes("dialog-input") {
            SubspaceDialogSurface(
                title = "Rename group",
                confirmLabel = "Rename",
                onConfirm = {},
                dismissLabel = "Cancel",
                onDismiss = {},
                icon = null,
                destructive = false,
                confirmEnabled = true,
            ) {
                OutlinedTextField(value = "Local configs", onValueChange = {}, singleLine = true)
            }
        }

    @Test
    fun searchField() =
        paparazzi.snapshotThemes("search-field") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SearchField(query = "", onQueryChange = {}, placeholder = "Search servers")
                SearchField(query = "ams", onQueryChange = {}, placeholder = "Search servers")
            }
        }

    @Test
    fun menu() =
        paparazzi.snapshotThemes("menu") {
            Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column {
                    MenuRows(
                        listOf(
                            MenuItem(SubspaceIcons.Edit, "Rename") {},
                            MenuItem(SubspaceIcons.Delete, "Delete", destructive = true) {},
                            MenuItem(SubspaceIcons.Add, "Add profile") {},
                        ),
                        onDismiss = {},
                    )
                }
            }
        }

    @Test
    fun statusChips() =
        paparazzi.snapshotThemes("status-chips") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(SubspaceIcons.AltRoute, "Smart route", onClick = {})
                StatusChip(SubspaceIcons.Apps, "All apps tunnelled", onClick = {})
            }
        }

    @Test
    fun sheetContent() =
        paparazzi.snapshotThemes("sheet") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(SheetShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(24.dp),
            ) {
                Text("Sheet title", style = MaterialTheme.typography.titleLarge)
                Text("Sheet content sits here.", style = MaterialTheme.typography.bodyMedium)
            }
        }

    @Test
    fun snackbar() =
        paparazzi.snapshotThemes("snackbar") {
            UndoSnackbar("Switched to Amsterdam Core", "Undo", onAction = {})
        }
}
