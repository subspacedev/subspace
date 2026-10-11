// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.component

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.R
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.SubspaceShapeTokens

private const val CARET_ROTATION_COLLAPSED = 0f
private const val CARET_ROTATION_EXPANDED = 180f

/**
 * A collapsible group of servers (M8.6 spec §4.1.1). The title row toggles it;
 * the overflow button opens [menuItems]. [header] stays visible whether the
 * group is open or not (a subscription's quota and last update go here);
 * [content] shows only when [expanded].
 */
@Suppress("LongParameterList")
@Composable
fun GroupCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    menuItems: List<MenuItem>,
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = SubspaceShapeTokens.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            GroupCardTitleRow(title, subtitle, expanded, onToggle, menuItems)
            header()
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(modifier = Modifier.padding(top = 8.dp), content = content)
            }
        }
    }
}

@Composable
private fun GroupCardTitleRow(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    menuItems: List<MenuItem>,
) {
    val toggleDescription =
        stringResource(
            if (expanded) R.string.group_card_collapse_description else R.string.group_card_expand_description,
            title,
            subtitle,
        )
    val caretRotation by
        animateFloatAsState(
            targetValue = if (expanded) CARET_ROTATION_EXPANDED else CARET_ROTATION_COLLAPSED,
            label = "group-card-caret-rotation",
        )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
            Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { contentDescription = toggleDescription },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painter = painterResource(SubspaceIcons.ExpandMore),
                // Decorative: the Row's own semantics already name the action once.
                contentDescription = null,
                modifier = Modifier.graphicsLayer { rotationZ = caretRotation },
            )
        }
        GroupCardOverflow(title, menuItems)
    }
}

@Composable
private fun GroupCardOverflow(
    title: String,
    items: List<MenuItem>,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                painter = painterResource(SubspaceIcons.MoreVert),
                contentDescription = stringResource(R.string.group_card_overflow_description, title),
            )
        }
        SubspaceMenu(expanded = open, onDismiss = { open = false }, items = items)
    }
}

/**
 * The "Updated 3 hours ago" line with its refresh button, for a subscription
 * group's [GroupCard] `header`. Each part shows only when its input is
 * non-null, so a group with neither draws nothing.
 */
@Composable
fun GroupCardUpdateRow(
    groupName: String,
    lastFetchedAtEpochMillis: Long?,
    onUpdate: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (lastFetchedAtEpochMillis == null && onUpdate == null) return
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lastFetchedAtEpochMillis != null) {
            val relative =
                DateUtils.getRelativeTimeSpanString(
                    lastFetchedAtEpochMillis,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString()
            Text(
                text = stringResource(R.string.group_card_last_updated, relative),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onUpdate != null) {
            IconButton(onClick = onUpdate) {
                Icon(
                    painter = painterResource(SubspaceIcons.Refresh),
                    contentDescription = stringResource(R.string.group_card_update_description, groupName),
                )
            }
        }
    }
}
