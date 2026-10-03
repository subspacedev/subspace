// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The card shape's top corners; a sheet is square at the bottom edge (M8.6 spec §4.2). */
internal val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * A modal bottom sheet in the card's top-corner shape. Unlike
 * [SubspaceBottomSheet] it carries no title: callers lay out their own content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubspaceSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
        shape = SheetShape,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        content = content,
    )
}
