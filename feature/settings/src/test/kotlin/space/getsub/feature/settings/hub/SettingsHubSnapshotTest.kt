// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.hub

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.model.DnsTransport
import space.getsub.core.model.PerAppMode
import space.getsub.core.model.PerAppSelection
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant
import space.getsub.feature.settings.SettingsActions
import space.getsub.feature.settings.SettingsNavigation
import space.getsub.feature.settings.SettingsState
import space.getsub.feature.settings.XrayVersionState

/** M8.6 spec §7.1: the Settings hub, disconnected and connected, in all four themes. */
class SettingsHubSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 4600))

    private val actions =
        SettingsActions(
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {},
        )
    private val navigation = SettingsNavigation({}, {}, {}, {})

    private val base =
        SettingsState(
            appVersion = "0.1.0-alpha01",
            xrayVersion = XrayVersionState.Available("v26.7.11"),
            dnsTransport = DnsTransport.DOH,
            dnsAddress = "https://dns.cloudflare.com/dns-query",
            activeRuleSetName = "Smart route",
            perApp = PerAppSelection(PerAppMode.AllowList, setOf("a.one", "a.two", "a.three")),
            failClosed = true,
            bootAutostart = false,
        )

    private fun shoot(
        name: String,
        state: SettingsState,
    ) {
        ThemeVariant.entries.forEach { v ->
            paparazzi.snapshot(name = "$name-${v.fileSuffix}") {
                SubspacePreviewTheme(v, reducedMotion = true) {
                    SettingsHubContent(state = state, actions = actions, navigation = navigation)
                }
            }
        }
    }

    @Test
    fun disconnected() = shoot("hub-disconnected", base)

    @Test
    fun connected() =
        shoot(
            "hub-connected",
            base.copy(
                sessionConnected = true,
                lightweightMode = true,
                perTagBreakdown = true,
                perApp = PerAppSelection.OFF,
                activeRuleSetName = null,
                hwidEnabled = false,
            ),
        )
}
