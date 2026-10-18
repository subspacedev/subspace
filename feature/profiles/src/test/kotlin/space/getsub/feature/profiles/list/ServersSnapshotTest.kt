// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.list

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.data.ProfileKind
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant

/**
 * M8.6 Part 3 Task 4: the Servers screen in all four themes — a populated list with every row
 * state, the empty state, and a search that matches nothing. Group cards start collapsed in a
 * real session, so the populated shots expand them explicitly.
 */
class ServersSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 4400))

    private val noActions =
        ServersActions(
            onQueryChanged = {},
            onProtocolFilterChanged = {},
            onSortChanged = {},
            onProfileSelected = {},
            onRenameGroup = { _, _ -> },
            onDeleteGroup = {},
            onAddProfile = {},
            onProfileEdit = {},
            onUpdateSubscription = {},
            onAllowRedirects = {},
            onDenyRedirects = {},
            onDismissUpdateResult = {},
            onOpenSubscriptionDetail = {},
            onTestProfile = {},
            onTestGroup = {},
            onCancelTests = {},
            onGroupSortChanged = { _, _ -> },
            onServersShown = {},
        )

    @Suppress("LongParameterList")
    private fun row(
        id: Long,
        name: String,
        transport: String = "reality · tcp",
        protocol: String = "VLESS",
        connectable: Boolean = true,
        active: Boolean = false,
        compatibility: Boolean = false,
        dropped: Boolean = false,
        latency: LatencyResult? = null,
        testing: Boolean = false,
    ) = ServerRow(
        id = id,
        name = name,
        protocol = protocol.lowercase(),
        protocolDisplay = protocol,
        transport = transport,
        kind = ProfileKind.TYPED,
        compatibilityMode = compatibility,
        connectable = connectable,
        isActive = active,
        droppedFromSubscriptionAt = if (dropped) DROPPED_AT else null,
        latency = latency,
        isTesting = testing,
    )

    private val subscriptionRows =
        listOf(
            row(1, "🇩🇪 Frankfurt", active = true, latency = LatencyResult.ok(42)),
            row(2, "🇳🇱 Amsterdam", transport = "ws · tls", latency = LatencyResult.ok(68)),
            row(
                3,
                "🇫🇮 Helsinki",
                transport = "ws · tls",
                protocol = "Trojan",
                connectable = false,
                latency = LatencyResult.failed(LatencyOutcome.UNSUPPORTED),
            ),
            row(4, "🇸🇪 Stockholm", dropped = true, latency = LatencyResult.ok(120)),
            row(5, "🇵🇱 Warsaw", compatibility = true, testing = true),
            row(6, "🇯🇵 Tokyo", latency = LatencyResult.failed(LatencyOutcome.TIMEOUT)),
        )

    private val manualRows =
        listOf(
            row(7, "Home lab", transport = "grpc · tls"),
            row(8, "Office relay", transport = "xhttp · tls", latency = LatencyResult.ok(25)),
        )

    private fun populated() =
        ServersState(
            groups =
            listOf(
                ServersGroup(
                    id = 1,
                    name = "Aurora VPN",
                    totalProfileCount = subscriptionRows.size,
                    profiles = subscriptionRows,
                    quotaUsedBytes = 12_400_000_000,
                    quotaTotalBytes = 50_000_000_000,
                    subscriptionId = 1,
                    // Relative to the test's own clock; three hours and a few ms floors to "3 hours ago".
                    lastFetchedAtEpochMillis = System.currentTimeMillis() - THREE_HOURS_MILLIS,
                    sort = SortOrder.AsListed,
                    sortFromProvider = true,
                ),
                ServersGroup(
                    id = 2,
                    name = "Local configs",
                    totalProfileCount = manualRows.size,
                    profiles = manualRows,
                    sort = SortOrder.Alphabetical,
                ),
            ),
            availableProtocols = listOf(ALL_PROTOCOLS_SENTINEL, "VLESS", "Trojan"),
            defaultSort = SortOrder.AsListed,
        )

    private fun shoot(
        name: String,
        state: ServersState,
        expanded: Set<Long> = emptySet(),
    ) {
        ThemeVariant.entries.forEach { v ->
            paparazzi.snapshot(name = "$name-${v.fileSuffix}") {
                SubspacePreviewTheme(v) {
                    ServersScreenContent(state = state, actions = noActions, initiallyExpanded = expanded)
                }
            }
        }
    }

    @Test
    fun twoGroupsWithEveryRowState() = shoot("servers-groups", populated(), expanded = setOf(1L, 2L))

    @Test
    fun untrustedCertificatePillKeepsTheName() =
        shoot(
            "servers-cert-untrusted",
            populated().let { s ->
                s.copy(
                    groups =
                    listOf(
                        s.groups[1].copy(
                            profiles =
                            listOf(
                                row(
                                    9,
                                    "🇩🇪 Frankfurt Hysteria",
                                    transport = "QUIC · 443",
                                    protocol = "Hysteria2",
                                    latency = LatencyResult.failed(LatencyOutcome.CERT_UNTRUSTED),
                                ),
                            ),
                        ),
                    ),
                )
            },
            expanded = setOf(2L),
        )

    @Test
    fun empty() = shoot("servers-empty", ServersState())

    @Test
    fun searchWithNoResults() =
        shoot(
            "servers-no-results",
            populated().let { s ->
                s.copy(
                    query = "zzz",
                    groups = s.groups.map { it.copy(profiles = emptyList()) },
                )
            },
            expanded = setOf(1L, 2L),
        )

    @Test
    fun redirectRequest() =
        shoot(
            "servers-redirect-request",
            populated().let { s ->
                s.copy(
                    groups =
                    s.groups.mapIndexed { i, g ->
                        if (i == 0) g.copy(redirectRequestHosts = listOf("new.example")) else g
                    },
                )
            },
        )

    @Test
    fun resolverRequest() =
        shoot(
            "servers-resolver-request",
            populated().let { s ->
                s.copy(
                    groups =
                    s.groups.mapIndexed { i, g ->
                        if (i == 0) g.copy(resolverRequestHost = "dns.example") else g
                    },
                )
            },
        )

    private companion object {
        const val DROPPED_AT = 1_700_000_000_000L
        const val THREE_HOURS_MILLIS = 3L * 60 * 60 * 1000
    }
}
