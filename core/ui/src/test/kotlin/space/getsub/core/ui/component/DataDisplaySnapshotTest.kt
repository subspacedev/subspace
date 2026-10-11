// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.snapshot.snapshotThemes

class DataDisplaySnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi()

    private val history = List(28) { i -> (i * 37L % 11) * 120_000 }

    private fun rate(bytesPerSecond: Long) = "%.1f KB/s".format(bytesPerSecond / 1_000.0)

    @Test
    fun statTiles() =
        paparazzi.snapshotThemes("stat-tiles") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(SubspaceIcons.SouthEast, "4.2 MB/s", "Down", Modifier.weight(1f))
                StatTile(SubspaceIcons.NorthEast, "310 KB/s", "Up", Modifier.weight(1f))
                StatTile(SubspaceIcons.NetworkPing, "42 ms", "Latency", Modifier.weight(1f), onClick = {})
            }
        }

    @Test
    fun throughput() =
        paparazzi.snapshotThemes("throughput") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThroughputCard(
                    history,
                    128_600L,
                    connected = true,
                    peakText = ::rate,
                    title = "Throughput",
                    windowLabel = "last hour",
                )
                ThroughputCard(
                    history,
                    128_600L,
                    connected = false,
                    peakText = ::rate,
                    title = "Throughput",
                    windowLabel = "last hour",
                )
            }
        }

    @Test
    fun quota() =
        paparazzi.snapshotThemes("quota") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                QuotaBar(0.25f, "12.4 GB of 50 GB used")
                QuotaBar(1.0f, "50 GB of 50 GB used")
                QuotaBar(1.3f, "65 GB of 50 GB used")
            }
        }

    private val menu =
        listOf(
            MenuItem(SubspaceIcons.Edit, "Rename", onClick = {}),
            MenuItem(SubspaceIcons.Delete, "Delete", destructive = true, onClick = {}),
        )

    @Composable
    private fun Rows() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ServerRow("🇩🇪", "Frankfurt Alpha", "VLESS · REALITY · TCP", "42 ms", false, true, 1, null, {}, {}, {})
            ServerRow("🇳🇱", "Amsterdam Beta", "VMESS · WS · TLS", "88 ms", true, true, 2, null, {}, {}, {})
            ServerRow(
                "🇫🇮", "Helsinki Edge", "TROJAN · WS · TLS", "—", false, false, null,
                "can't connect with this build", {}, {}, {},
            )
            ServerRow(
                "🇸🇪", "Stockholm Gamma", "VLESS · XHTTP · TLS", "120 ms", false, true, 3,
                "Compatibility mode", {}, {}, {}, noteTone = NoteTone.Info,
            )
        }
    }

    @Test
    fun groupCard() =
        paparazzi.snapshotThemes("group-card") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GroupCard("Local configs", "3 servers", expanded = false, onToggle = {}, menuItems = menu) {}
                GroupCard(
                    title = "My subscription",
                    subtitle = "3 servers",
                    expanded = true,
                    onToggle = {},
                    menuItems = menu,
                    header = { QuotaBar(0.25f, "12.4 GB of 50 GB used", Modifier) },
                ) { Rows() }
            }
        }

    @Test
    fun serverRow() = paparazzi.snapshotThemes("server-row") { Rows() }
}
