// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MatchingDeclarationName")

package space.getsub.core.ui.component

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.R
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.SubspaceShapeTokens

/** Where a connection to an app ends up (M8.6 spec §3.1). */
enum class Outcome(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
) {
    Tunnelled(SubspaceIcons.Shield, R.string.outcome_tunnelled),
    Direct(SubspaceIcons.ArrowOutward, R.string.outcome_direct),
    Proxy(SubspaceIcons.Shield, R.string.outcome_proxy),
    Block(SubspaceIcons.Block, R.string.outcome_block),
}

/** A small pill saying what happens to an app's traffic. */
@Composable
fun OutcomeTag(
    outcome: Outcome,
    modifier: Modifier = Modifier,
) {
    val s = MaterialTheme.colorScheme
    val (container, content) =
        when (outcome) {
            Outcome.Tunnelled, Outcome.Proxy -> s.tertiaryContainer to s.onTertiaryContainer
            Outcome.Direct -> s.surfaceContainerHighest to s.onSurfaceVariant
            Outcome.Block -> s.errorContainer to s.onErrorContainer
        }
    Surface(modifier = modifier, shape = SubspaceShapeTokens.pill, color = container, contentColor = content) {
        Row(
            Modifier.heightIn(min = 30.dp).padding(start = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(painterResource(outcome.icon), contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                stringResource(outcome.label),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
