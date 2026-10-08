// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.model.DnsState
import space.getsub.core.model.RoutingSourceKind
import space.getsub.core.model.RuleSetAssetFailure
import space.getsub.core.model.RuleSetAssetState
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant

/** M8.6 Part 4 Task 5: the rule-set list and its empty state, in all four themes. */
class RoutingSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 3200))

    private val noActions =
        RoutingListActions(onActivate = {}, onDelete = {}, onCreateRuleSet = {}, onEditRuleSet = {}, onBack = {})

    private val rows =
        listOf(
            RuleSetRow(
                id = 1,
                name = "ads",
                entryCount = 3,
                isActive = false,
                missingGeoFiles = setOf("geosite.dat"),
            ),
            RuleSetRow(
                id = 2,
                name = "lan",
                entryCount = 1,
                isActive = true,
                missingGeoFiles = emptySet(),
                hasFailedGeoUpdate = true,
            ),
            RuleSetRow(
                id = 3,
                name = "vpn",
                entryCount = 12,
                isActive = false,
                missingGeoFiles = emptySet(),
                sourceKind = RoutingSourceKind.Header,
                subscriptionName = "NameVPN",
                assetState = RuleSetAssetState.Ready,
                requiresGeoFiles = true,
                dnsState = DnsState.Applied,
            ),
            RuleSetRow(
                id = 4,
                name = "geo",
                entryCount = 2,
                isActive = false,
                missingGeoFiles = emptySet(),
                sourceKind = RoutingSourceKind.Deeplink,
                assetState = RuleSetAssetState.Pending,
                requiresGeoFiles = true,
                downloadProgress = GeoProgress(12_000_000, 23_000_000),
            ),
            RuleSetRow(
                id = 5,
                name = "prep",
                entryCount = 4,
                isActive = false,
                missingGeoFiles = emptySet(),
                sourceKind = RoutingSourceKind.Qr,
                assetState = RuleSetAssetState.Pending,
                requiresGeoFiles = true,
            ),
            RuleSetRow(
                id = 6,
                name = "slow",
                entryCount = 1,
                isActive = false,
                missingGeoFiles = emptySet(),
                sourceKind = RoutingSourceKind.Clipboard,
                assetState = RuleSetAssetState.Failed,
                assetFailure = RuleSetAssetFailure.TimedOut,
            ),
        )

    private fun shootList(
        name: String,
        state: RoutingState,
    ) {
        ThemeVariant.entries.forEach { v ->
            paparazzi.snapshot(name = "$name-${v.fileSuffix}") {
                SubspacePreviewTheme(v) { RoutingListScreenContent(state = state, actions = noActions) }
            }
        }
    }

    @Test
    fun ruleSetList() = shootList("routing-list", RoutingState(ruleSets = rows, activeRuleSetId = 2))

    @Test
    fun emptyList() = shootList("routing-empty", RoutingState())
}
