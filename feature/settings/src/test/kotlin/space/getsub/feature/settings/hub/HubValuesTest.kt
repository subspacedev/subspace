// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.hub

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.data.InstalledGeoAsset
import space.getsub.core.data.ThemePreference
import space.getsub.core.model.DnsTransport
import space.getsub.core.model.GeoDataKind
import space.getsub.core.model.PerAppMode
import space.getsub.core.model.PerAppSelection
import space.getsub.core.model.PingMode
import space.getsub.feature.settings.SettingsState

class HubValuesTest {
    private val base = SettingsState()

    private fun asset(name: String) =
        InstalledGeoAsset(
            fileName = name,
            sourceUrl = "https://example.invalid/$name",
            geoType = GeoDataKind.IP,
            sizeBytes = 1L,
            installedAt = 1L,
            lastAttemptedAt = 1L,
            lastFailure = null,
        )

    @Test
    fun `routing is Off with no active rule set`() {
        base.routingValue() shouldBe HubValue.Off
    }

    @Test
    fun `routing names the active rule set`() {
        base.copy(activeRuleSetName = "Work").routingValue() shouldBe HubValue.Active("Work")
    }

    @Test
    fun `per-app is Off for the default selection`() {
        base.perAppValue() shouldBe HubValue.Off
    }

    @Test
    fun `per-app counts an allow-list`() {
        val s = base.copy(perApp = PerAppSelection(PerAppMode.AllowList, setOf("a", "b")))
        s.perAppValue() shouldBe HubValue.AllowList(2)
    }

    @Test
    fun `per-app counts a deny-list`() {
        val s = base.copy(perApp = PerAppSelection(PerAppMode.DenyList, setOf("a")))
        s.perAppValue() shouldBe HubValue.DenyList(1)
    }

    @Test
    fun `appearance device id latency and dns read their fields`() {
        val s =
            base.copy(
                theme = ThemePreference.Dark,
                hwidEnabled = false,
                pingMode = PingMode.PROXY_HEAD,
                pingTimeoutSeconds = 7,
                dnsTransport = DnsTransport.DOU,
                dnsAddress = "1.1.1.1",
            )
        s.appearanceValue() shouldBe ThemePreference.Dark
        s.deviceIdValue() shouldBe false
        base.deviceIdValue() shouldBe true
        s.latencyValue() shouldBe (PingMode.PROXY_HEAD to 7)
        s.dnsValue() shouldBe (DnsTransport.DOU to "1.1.1.1")
    }

    @Test
    fun `geo lists installed file names`() {
        val s = base.copy(geoInstalledAssets = listOf(asset("geoip.dat"), asset("geosite.dat")))
        s.geoValue() shouldBe listOf("geoip.dat", "geosite.dat")
    }

    @Test
    fun `geo falls back to source names when nothing is installed`() {
        base.geoValue() shouldBe base.geoRows.map { it.displayName }
    }

    @Test
    fun `session log follows lightweight mode`() {
        base.sessionLogValue() shouldBe SessionLogValue.Recording
        base.copy(lightweightMode = true).sessionLogValue() shouldBe SessionLogValue.NotRecordingLightweight
    }

    @Test
    fun `kill switch extra line needs fail-closed and a per-app mode`() {
        val on = PerAppSelection(PerAppMode.DenyList, setOf("a"))
        base.copy(failClosed = true, perApp = on).killSwitchExtraLine() shouldBe true
        base.copy(failClosed = false, perApp = on).killSwitchExtraLine() shouldBe false
        base.copy(failClosed = true, perApp = PerAppSelection.OFF).killSwitchExtraLine() shouldBe false
    }

    @Test
    fun `lightweight extra line needs lightweight mode and a live session`() {
        base.copy(lightweightMode = true, sessionConnected = true).lightweightExtraLine() shouldBe true
        base.copy(lightweightMode = true, sessionConnected = false).lightweightExtraLine() shouldBe false
        base.copy(lightweightMode = false, sessionConnected = true).lightweightExtraLine() shouldBe false
    }
}
