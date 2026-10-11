// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.Health
import space.getsub.core.model.PerAppMode
import space.getsub.core.model.PerAppSelection
import space.getsub.core.model.TagTraffic
import space.getsub.core.model.ThroughputWindow
import space.getsub.core.model.TrafficSample
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant

/** M8.6 spec §7.1: Home in representative states, in all four themes. Fixed clock: goldens never tick. */
class HomeSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 3200))

    private val since = 1_000_000L
    private val now = { since + 724_000L } // "Up 12:04"
    private val actions = HomeActions({}, {}, {}, {}, {}, {}, {}, {})

    private fun shoot(
        name: String,
        state: HomeState,
    ) {
        ThemeVariant.entries.forEach { v ->
            paparazzi.snapshot(name = "$name-${v.fileSuffix}") {
                SubspacePreviewTheme(v) { HomeScreenContent(state, actions, nowMillis = now) }
            }
        }
    }

    @Test
    fun disconnected() = shoot("home-disconnected", HomeState())

    @Test
    fun connectedWithHistoryAndBreakdown() =
        shoot(
            "home-connected",
            HomeState(
                connection = ConnectionState.Connected(since, 1080, 0, Health.Open),
                traffic =
                TrafficSample(
                    uplinkBytes = 310_000,
                    downlinkBytes = 4_200_000,
                    uplinkPackets = 0,
                    downlinkPackets = 0,
                    perTag = listOf(TagTraffic("proxy", 310_000, 4_200_000), TagTraffic("direct", 88_000, 1_100_000)),
                    throughput = List(ThroughputWindow.BUCKET_COUNT) { i -> (i % 7 + 1) * 1_000_000L },
                ),
                activeProfile = snapshotProfile("\uD83C\uDDE9\uD83C\uDDEA Frankfurt"),
                hasAnyProfile = true,
                perTagBreakdownEnabled = true,
                activeRuleSetName = "Smart route",
                perApp = PerAppSelection(PerAppMode.AllowList, setOf("a", "b", "c")),
            ),
        )

    @Test
    fun blocked() =
        shoot(
            "home-blocked",
            HomeState(connection = ConnectionState.Reconnecting(FailureReason.CoreStartFailed, 3, blocked = true)),
        )

    @Test
    fun lightweightHidesTraffic() =
        shoot(
            "home-lightweight",
            HomeState(
                connection = ConnectionState.Connected(since, 1080, 0, Health.Open),
                traffic =
                TrafficSample(
                    uplinkBytes = 310_000,
                    downlinkBytes = 4_200_000,
                    uplinkPackets = 0,
                    downlinkPackets = 0,
                    throughput = List(ThroughputWindow.BUCKET_COUNT) { i -> (i % 7 + 1) * 1_000_000L },
                ),
                trafficHidden = true,
            ),
        )
}
