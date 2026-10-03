// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.snapshot.snapshotThemes

/** ButtonGroup drops items that do not fit; at 2x font on the default width all three tiles must remain. */
class ModeTilesLargeFontSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_5.copy(fontScale = 2f))

    @Test
    fun modeTilesAtDoubleFontScale() =
        paparazzi.snapshotThemes("mode-tiles-2x") {
            ModeTiles(
                options =
                listOf(
                    ModeTile(SubspaceIcons.SelectAll, "Off", "Every app"),
                    ModeTile(SubspaceIcons.PlaylistAddCheck, "Allow-list", "Only these"),
                    ModeTile(SubspaceIcons.Block, "Deny-list", "All but these"),
                ),
                selected = 1,
                onSelect = {},
            )
        }
}
