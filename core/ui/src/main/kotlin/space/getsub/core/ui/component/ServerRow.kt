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
 * 36 dp [IconButton]. A row that cannot connect ([enabled] false) shows [note]
 * — the reason — before anything else (fix S2) and dims its flag, name, detail
 * and latency to 0.6 alpha. The note itself is never dimmed: it is the one
 * thing on the row the user needs to read, in `error`, and 0.6 alpha took it
 * to 2.83:1. The edit button stays at full strength too, since editing is how
 * the user fixes the row.
 *
 * [enabled] false disables only the latency pill: there is nothing to re-test
 * on a server this build cannot run. The row itself stays selectable, because
 * picking a server is a settings write and the service refuses an unrunnable
 * one on its own (M8.6 Part 3 review focus 4); a dead row that ignores taps
 * would hide that decision from the user.
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
    val dim = Modifier.alpha(if (enabled) 1f else UNSUPPORTED_ALPHA)
    Surface(
        shape = SubspaceShapeTokens.tile,
        color = if (selected) scheme.secondaryContainer else scheme.surfaceContainer,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .clickable(role = Role.Button, onClick = onSelect)
                .heightIn(min = 64.dp)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(flag, style = MaterialTheme.typography.titleLarge, modifier = dim)
            ServerRowText(note, rank, name, detail, dim, Modifier.weight(1f))
            LatencyPill(latencyText, enabled, onTestLatency, dim)
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

/** The note (never dimmed), then rank and name, then detail. */
@Suppress("LongParameterList")
@Composable
private fun ServerRowText(
    note: String?,
    rank: Int?,
    name: String,
    detail: String,
    dim: Modifier,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        if (note != null) {
            Text(note, style = MaterialTheme.typography.labelMedium, color = scheme.error)
        }
        Row(
            modifier = dim,
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
            modifier = dim,
        )
    }
}

@Composable
private fun LatencyPill(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = SubspaceShapeTokens.pill,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.heightIn(min = 32.dp),
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge.tabular())
        }
    }
}
