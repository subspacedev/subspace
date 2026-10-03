// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.R
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.SubspaceShapeTokens
import space.getsub.core.ui.theme.mono
import space.getsub.core.ui.theme.tabular

private const val UNSUPPORTED_ALPHA = 0.6f

/**
 * One server in a group (M8.6 spec §4.9). The row selects; the latency pill is
 * a separate 32 dp target that re-tests without selecting (fix A7); edit is a
 * 36 dp [IconButton]. A row that cannot connect ([enabled] false) dims to 0.6
 * alpha and shows [note] — the reason — before anything else (fix S2).
 */
@Suppress("LongParameterList")
@Composable
fun ServerRow(
    flag: String,
    name: String,
    detail: String,
    latencyText: String,
    selected: Boolean,
    enabled: Boolean,
    rank: Int?,
    note: String?,
    onSelect: () -> Unit,
    onTestLatency: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = SubspaceShapeTokens.tile,
        color = if (selected) scheme.secondaryContainer else scheme.surfaceContainer,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
        modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else UNSUPPORTED_ALPHA),
    ) {
        Row(
            Modifier
                .clickable(enabled = enabled, role = Role.Button, onClick = onSelect)
                .heightIn(min = 64.dp)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(flag, style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f)) {
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.labelMedium, color = scheme.error)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (rank != null) {
                        Text(
                            "#$rank",
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = scheme.primary,
                        )
                    }
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium.mono(),
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LatencyPill(latencyText, enabled, onTestLatency)
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    painterResource(SubspaceIcons.Edit),
                    contentDescription = stringResource(R.string.server_edit, name),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun LatencyPill(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = SubspaceShapeTokens.pill,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.heightIn(min = 32.dp),
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge.tabular())
        }
    }
}
