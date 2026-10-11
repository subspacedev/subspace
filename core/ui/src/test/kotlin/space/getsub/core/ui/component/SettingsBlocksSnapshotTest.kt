// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.snapshot.snapshotThemes

class SettingsBlocksSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun sectionTitle() = paparazzi.snapshotThemes("section-title") { SectionTitle("Traffic") }

    @Test
    fun hubRow() =
        paparazzi.snapshotThemes("hub-row") {
            Column {
                HubRow(SubspaceIcons.AltRoute, "Routing", "Smart route active", onClick = {})
                HubRow(SubspaceIcons.AltRoute, "Routing", null, onClick = {})
            }
        }

    @Test
    fun switchRow() =
        paparazzi.snapshotThemes("switch-row") {
            Column {
                SwitchRow(
                    SubspaceIcons.Block,
                    "Kill switch",
                    "Block traffic if the tunnel drops",
                    checked = false,
                    onCheckedChange = {},
                )
                SwitchRow(
                    SubspaceIcons.Block,
                    "Kill switch",
                    "Block traffic if the tunnel drops",
                    checked = true,
                    onCheckedChange = {},
                )
                SwitchRow(
                    SubspaceIcons.Block,
                    "Kill switch",
                    "Block traffic if the tunnel drops",
                    checked = true,
                    onCheckedChange = {},
                    note = "Apps outside the tunnel are not covered.",
                )
                Column(Modifier.background(MaterialTheme.colorScheme.tertiaryContainer).padding(16.dp)) {
                    SwitchRow(
                        SubspaceIcons.Shield,
                        "Always on",
                        "Reconnect after a restart",
                        checked = true,
                        onCheckedChange = {},
                        colors = SwitchRowDefaults.onTertiaryContainer(),
                    )
                    SwitchRow(
                        SubspaceIcons.Shield,
                        "Always on",
                        "Reconnect after a restart",
                        checked = false,
                        onCheckedChange = {},
                        colors = SwitchRowDefaults.onTertiaryContainer(),
                    )
                }
            }
        }

    @Test
    fun sliderRow() =
        paparazzi.snapshotThemes("slider-row") {
            SliderRow("Timeout", 5, 1..15, valueText = { "$it s" }, onValueChange = {})
        }

    @Test
    fun tonalBanner() =
        paparazzi.snapshotThemes("tonal-banner") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BannerTone.entries.forEach {
                    TonalBanner(
                        SubspaceIcons.Warning,
                        "Kill switch is holding traffic until the tunnel is back.",
                        tone = it,
                    )
                }
            }
        }

    @Test
    fun outcomeTag() =
        paparazzi.snapshotThemes("outcome-tag") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Outcome.entries.take(3).forEach { OutcomeTag(it) }
                }
                OutcomeTag(Outcome.Block)
            }
        }

    @Test
    fun modeTiles() =
        paparazzi.snapshotThemes("mode-tiles") {
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
