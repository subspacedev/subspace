// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.snapshot.snapshotThemes

/** The worst-case value in a three-tile row at 360 dp and 2x font: shown whole, never clipped. */
class StatTileLargeFontSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_5.copy(fontScale = 2f))

    @Test
    fun worstCaseValueAtDoubleFontScale() =
        paparazzi.snapshotThemes("stat-tiles-2x") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(SubspaceIcons.SouthEast, "1023.9 GB", "Down", Modifier.weight(1f))
                StatTile(SubspaceIcons.NorthEast, "1023.9 GB", "Up", Modifier.weight(1f))
                StatTile(SubspaceIcons.NetworkPing, "42 ms", "Latency", Modifier.weight(1f), onClick = {})
            }
        }
}
