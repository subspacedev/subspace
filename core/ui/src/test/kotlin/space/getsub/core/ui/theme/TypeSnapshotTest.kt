// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.snapshot.snapshotThemes

class TypeSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun roles() =
        paparazzi.snapshotThemes("type") {
            val t = MaterialTheme.typography
            Column {
                Text("Headline medium", style = t.headlineMedium)
                Text("Title large", style = t.titleLarge)
                Text("Title medium", style = t.titleMedium)
                Text("Body large, the default for prose", style = t.bodyLarge)
                Text("Body medium, supporting text", style = t.bodyMedium)
                Text("Label large", style = t.labelLarge)
                Text("Label medium", style = t.labelMedium)
                Text("203.0.113.7:443 · REALITY · TCP", style = t.bodyMedium.mono())
                Text("12:04:01  ↓ 4.2 MB", style = t.titleLarge.tabular())
            }
        }
}
