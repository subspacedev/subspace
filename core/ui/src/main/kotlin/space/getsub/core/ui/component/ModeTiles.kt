// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MatchingDeclarationName")

package space.getsub.core.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.theme.SubspaceShapeTokens

/** One option of [ModeTiles]. */
data class ModeTile(
    @DrawableRes val icon: Int,
    val label: String,
    val sub: String? = null,
)

/**
 * Pick exactly one of a few modes, as a connected button group of tiles
 * (M8.6 spec §3.1). Each tile is a radio button for accessibility.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModeTiles(
    options: List<ModeTile>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    ButtonGroup(
        overflowIndicator = {},
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val scope = this
        options.forEachIndexed { index, option ->
            customItem(
                buttonGroupContent = {
                    // Equal-width tiles: weight is a ButtonGroupScope modifier, so borrow the scope.
                    Tile(
                        option,
                        selected = index == selected,
                        onClick = { onSelect(index) },
                        modifier = with(scope) { Modifier.weight(1f) },
                    )
                },
                menuContent = {},
            )
        }
    }
}

@Composable
private fun Tile(
    option: ModeTile,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = MaterialTheme.colorScheme
    Surface(
        shape = SubspaceShapeTokens.tile,
        color = if (selected) s.secondaryContainer else Color.Transparent,
        contentColor = if (selected) s.onSecondaryContainer else s.onSurface,
        border = BorderStroke(1.dp, if (selected) s.secondaryContainer else s.outlineVariant),
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp)
                .padding(start = 12.dp, top = 14.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(painterResource(option.icon), contentDescription = null, modifier = Modifier.size(24.dp))
            Column {
                Text(option.label, style = MaterialTheme.typography.titleSmall)
                if (option.sub != null) Text(option.sub, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
