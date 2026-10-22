// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.data.InstalledGeoAsset
import space.getsub.core.data.ResolvedTuning
import space.getsub.core.data.TuningValue
import space.getsub.core.model.DnsTransport
import space.getsub.core.model.ExcludeRoutesUserSetting
import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.GeoDataKind
import space.getsub.core.model.GeoSourceCatalogue
import space.getsub.core.model.MuxSpec
import space.getsub.core.model.MuxUserSettings
import space.getsub.core.model.NoiseMode
import space.getsub.core.model.NoisePacketType
import space.getsub.core.model.NoiseSettings
import space.getsub.core.model.PreResolveSettings
import space.getsub.core.model.ProxyAuthMode
import space.getsub.core.model.ProxyAuthSettings
import space.getsub.core.model.SniffingRuleWarning
import space.getsub.core.model.SniffingUserSetting
import space.getsub.core.model.TunMtuUserSetting
import space.getsub.core.model.TuningOrigin
import space.getsub.core.model.Udp443Mode
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant
import space.getsub.feature.settings.SettingsActions
import space.getsub.feature.settings.SettingsPageContent
import space.getsub.feature.settings.SettingsPageId
import space.getsub.feature.settings.SettingsState
import space.getsub.feature.settings.XrayVersionState

/** M8.6 spec §7.1: each Settings sub-page, in all four themes, at 360 dp wide. */
class SettingsPagesSnapshotTest {
    // PIXEL_5 is 393 dp wide; 990 px at 2.75x is the 360 dp the layouts must hold.
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 990, screenHeight = 2400))

    private val actions =
        SettingsActions(
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {},
        )

    private val base =
        SettingsState(
            appVersion = "0.1.0-alpha01",
            xrayVersion = XrayVersionState.Available("v26.7.11"),
            dnsTransport = DnsTransport.DOH,
            dnsAddress = "https://dns.cloudflare.com/dns-query",
            dnsBootstrapIp = "1.1.1.1",
            hwid = "K7dQ2mX9pL4nR8vT1yU3wA6sD5fG0hJ2kZ9xC4vB7nM",
            hwidEnabled = true,
            dynamicColorAvailable = true,
            pingCheckUrl = "https://www.gstatic.com/generate_204",
            pingTimeoutSeconds = 5,
            pingOnLaunch = true,
            pingOnLaunchMetered = false,
        )

    private fun shoot(
        page: SettingsPageId,
        state: SettingsState = base,
    ) {
        ThemeVariant.entries.forEach { v ->
            paparazzi.snapshot(name = "${page.name.lowercase()}-${v.fileSuffix}") {
                SubspacePreviewTheme(v, reducedMotion = true) {
                    SettingsPageContent(page = page, state = state, actions = actions, onBack = {})
                }
            }
        }
    }

    @Test
    fun appearance() = shoot(SettingsPageId.Appearance)

    @Test
    fun deviceId() = shoot(SettingsPageId.DeviceId)

    @Test
    fun latency() = shoot(SettingsPageId.Latency)

    @Test
    fun dns() = shoot(SettingsPageId.Dns, base.copy(dnsOverriddenByProfile = true))

    @Test
    fun about() = shoot(SettingsPageId.About)

    /**
     * M9-A spec §2.3: fragmentation On shows its fields; noise stays Off and says the provider's apply.
     * Connected, so both rows carry the "from your next connection" note.
     */
    @Test
    fun censorshipFragmentOn() =
        shoot(
            SettingsPageId.Censorship,
            base.copy(fragment = FragmentSettings.DEFAULT.copy(enabled = true), sessionConnected = true),
        )

    /** Noise On in Fixed mode: the packet-type list and the packet field. */
    @Test
    fun censorshipNoiseFixed() =
        shoot(
            SettingsPageId.Censorship,
            base.copy(
                noise =
                NoiseSettings.DEFAULT.copy(
                    enabled = true,
                    mode = NoiseMode.Fixed,
                    packetType = NoisePacketType.Hex,
                    packet = "0a0b0c0d",
                ),
            ),
        )

    /** M9-C: server pre-resolution On shows its DoH URL and bootstrap IP fields. */
    @Test
    fun censorshipPreResolveOn() =
        shoot(
            SettingsPageId.Censorship,
            base.copy(preResolve = PreResolveSettings.DEFAULT.copy(enabled = true), sessionConnected = true),
        )

    @Test
    fun geo() {
        // The page is long: render it whole rather than clipped at the screen height.
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(screenWidth = 990, screenHeight = 4400))
        val defaults = GeoSourceCatalogue.defaults()
        val ip = defaults.first { it.geoType == GeoDataKind.IP }
        val domain = defaults.first { it.geoType == GeoDataKind.DOMAIN }
        shoot(
            SettingsPageId.Geo,
            base.copy(
                selectedGeoSourceIds = defaults.map { it.id }.toSet(),
                geoInstalledAssets =
                listOf(
                    InstalledGeoAsset(
                        fileName = ip.installFileName,
                        sourceUrl = ip.downloadUrl,
                        geoType = ip.geoType,
                        sizeBytes = 23_080_744,
                        installedAt = 1_760_000_000_000,
                        lastAttemptedAt = 1_760_000_000_000,
                        lastFailure = null,
                    ),
                    InstalledGeoAsset(
                        fileName = domain.installFileName,
                        sourceUrl = domain.downloadUrl,
                        geoType = domain.geoType,
                        sizeBytes = 2_400_000,
                        installedAt = 1_760_000_000_000,
                        lastAttemptedAt = 1_760_100_000_000,
                        lastFailure = "DownloadFailed",
                    ),
                    InstalledGeoAsset(
                        fileName = "extra-geosite.dat",
                        sourceUrl = "https://example.org/extra-geosite.dat",
                        geoType = GeoDataKind.DOMAIN,
                        sizeBytes = 1_200_000,
                        installedAt = 1_760_000_000_000,
                        lastAttemptedAt = 1_760_000_000_000,
                        lastFailure = null,
                    ),
                ),
            ),
        )
    }

    /** M9-E: every switch off, each showing what the active server's subscription sets. */
    @Test
    fun tunnelDefault() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(screenWidth = 990, screenHeight = 3600))
        shoot(
            SettingsPageId.Tunnel,
            base.copy(
                tunnelWithoutYourOverrides =
                ResolvedTuning(
                    sniffing = TuningValue(false, TuningOrigin.Subscription),
                    mux = TuningValue(MuxSpec(8, 16, Udp443Mode.Reject), TuningOrigin.Subscription),
                    tunMtu = TuningValue(8500, TuningOrigin.Default),
                    excludedRoutes = TuningValue(emptyList(), TuningOrigin.Default),
                ),
                sniffingOffWarning = SniffingRuleWarning.DomainRulesStop,
            ),
        )
    }

    /**
     * M9-E: every section on, connected. The stored text is invalid on purpose so the field errors
     * show: an MTU under 1280, a bad excluded-route line, and an empty manual password.
     */
    @Test
    fun tunnelCustom() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(screenWidth = 990, screenHeight = 5600))
        shoot(
            SettingsPageId.Tunnel,
            base.copy(
                sessionConnected = true,
                tunnel =
                TunnelDraft(
                    sniffing = SniffingUserSetting(override = true, enabled = false),
                    mux = MuxUserSettings(override = true, enabled = true, "-1", "2000", "skip"),
                    mtu = TunMtuUserSetting(override = true, value = "1000"),
                    routes = ExcludeRoutesUserSetting(override = true, ranges = "10.0.0.0/8\n10.0.0.0/33"),
                    proxyAuth = ProxyAuthSettings(ProxyAuthMode.Manual, "subspace", ""),
                ),
            ),
        )
    }
}
