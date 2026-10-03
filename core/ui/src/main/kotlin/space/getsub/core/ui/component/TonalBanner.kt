// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MatchingDeclarationName")

package space.getsub.core.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.theme.SubspaceShapeTokens

enum class BannerTone { Neutral, Warning, Blocked }

/** An inline notice on a tonal container; [tone] picks the container role (M8.6 spec §3.1). */
@Composable
fun TonalBanner(
    @DrawableRes icon: Int,
    text: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Neutral,
) {
    val s = MaterialTheme.colorScheme
    val (container, content) =
        when (tone) {
            BannerTone.Neutral -> s.surfaceContainerHigh to s.onSurface
            BannerTone.Warning -> s.tertiaryContainer to s.onTertiaryContainer
            BannerTone.Blocked -> s.errorContainer to s.onErrorContainer
        }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SubspaceShapeTokens.bubble,
        color = container,
        contentColor = content,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
