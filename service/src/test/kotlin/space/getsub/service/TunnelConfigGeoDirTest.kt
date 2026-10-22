// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.VlessOutbound
import space.getsub.core.xray.ConfigResult
import space.getsub.core.xray.TunnelSettings
import space.getsub.core.xray.XrayConfigGenerator
import java.io.File
import java.nio.file.Files

/**
 * libXray v26.10.10 ignores the invoke-level `env`; the asset location reaches the core only
 * through the config's own root `env` block (stock-upgrade spec §2). [typedConfigGeoDir] is what
 * `TunnelService.startCore` puts on [TunnelSettings.geoAssetDir], so this pins the typed config
 * the tunnel hands `XrayController.validate`/`start` — `TunnelService` itself needs a device.
 */
class TunnelConfigGeoDirTest {
    private val reality =
        Security.Reality(
            serverName = "www.microsoft.com",
            publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
            shortId = "0123abcd",
            fingerprint = "chrome",
            spiderX = "/",
        )

    private val outbound =
        VlessOutbound(
            address = "example.com",
            port = 443,
            uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab",
            flow = "xtls-rprx-vision",
            stream = StreamSettings(network = "tcp", security = reality),
        )

    private val profile = Profile(id = "p1", name = "Test", outbound = outbound)

    private fun configFor(geoDir: String?): String {
        val settings =
            TunnelSettings(
                socksPort = 41080,
                dnsServer = "1.1.1.1",
                enableSniffing = true,
                proxyCredentials = TEST_CREDENTIALS,
                httpPort = 41081,
                geoAssetDir = geoDir,
            )
        return (XrayConfigGenerator.generate(profile, settings) as ConfigResult.Ok).json
    }

    private fun envAsset(json: String): String? =
        Json
            .parseToJsonElement(json)
            .jsonObject["env"]
            ?.jsonObject
            ?.get("xray.location.asset")
            ?.jsonPrimitive
            ?.content

    private fun installedRoot(): File =
        Files.createTempDirectory("tunnel-geo").toFile().apply { File(this, "geosite.dat").writeText("x") }

    @Test
    fun `tunnelStartPassesGeoDirIntoTheConfig`() {
        val root = installedRoot()

        val json = configFor(typedConfigGeoDir(routingAssetDir = null, flatRoot = root))

        envAsset(json) shouldBe root.absolutePath
    }

    @Test
    fun `active routing names its own asset directory, not the flat root`() {
        val root = installedRoot()
        val generation = File(root, "sets/1/7").apply { mkdirs() }

        val json = configFor(typedConfigGeoDir(routingAssetDir = generation, flatRoot = root))

        envAsset(json) shouldBe generation.absolutePath
    }

    // Review Focus 4: a fresh install has no geo files yet, and the config still builds — with
    // no `env` block at all rather than one naming a directory that holds nothing.
    @Test
    fun `no installed geo files means no env block`() {
        val missing = File(Files.createTempDirectory("tunnel-geo-missing").toFile(), "geo")
        val empty = Files.createTempDirectory("tunnel-geo-empty").toFile()

        typedConfigGeoDir(routingAssetDir = null, flatRoot = missing) shouldBe null
        typedConfigGeoDir(routingAssetDir = null, flatRoot = empty) shouldBe null
        envAsset(configFor(typedConfigGeoDir(routingAssetDir = null, flatRoot = empty))) shouldBe null
    }
}
