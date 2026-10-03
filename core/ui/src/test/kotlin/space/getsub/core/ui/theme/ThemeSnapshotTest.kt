// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.snapshot.snapshotThemes

class ThemeSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun palette() =
        paparazzi.snapshotThemes("palette") {
            val s = MaterialTheme.colorScheme
            val pop = LocalSubspaceColors.current
            Column {
                Swatch("surfaceContainerLow", s.surfaceContainerLow, s.onSurface)
                Swatch("surfaceContainer", s.surfaceContainer, s.onSurface)
                Swatch("surfaceContainerHigh", s.surfaceContainerHigh, s.onSurface)
                Swatch("surfaceContainerHighest", s.surfaceContainerHighest, s.onSurfaceVariant)
                Swatch("primary", s.primary, s.onPrimary)
                Swatch("secondaryContainer", s.secondaryContainer, s.onSecondaryContainer)
                Swatch("tertiary (connected)", s.tertiary, s.onTertiary)
                Swatch("tertiaryContainer", s.tertiaryContainer, s.onTertiaryContainer)
                Swatch("error", s.error, s.onError)
                Swatch("pop", pop.pop, pop.onPop)
            }
        }
}

@Composable
private fun Swatch(
    name: String,
    bg: Color,
    fg: Color,
) {
    Text(
        name,
        color = fg,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().background(bg).padding(12.dp),
    )
}
