// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import space.getsub.core.parser.OverrideTarget

/**
 * M9-E spec §2: the sniffing switch on both config paths. Off drops the block, except that a
 * FakeDNS plan keeps `fakedns` alone; a raw config's own block survives a provider value and is
 * replaced only by the user's override.
 */
class SniffingSwitchTest {
    private val settings =
        TunnelSettings(
            socksPort = 10808,
            dnsServer = "1.1.1.1",
            enableSniffing = true,
            proxyCredentials = TEST_CREDENTIALS,
        )

    private val fakeDnsPlan =
        DnsPlan(
            servers = listOf(DnsServerSpec(address = "1.1.1.1")),
            hosts = emptyMap(),
            fakeDns = true,
            directMatch = null,
            proxyMatch = null,
        )

    private val withOwnBlock =
        """
        {
          "inbounds": [
            { "tag": "socks", "port": 10808, "protocol": "socks",
              "sniffing": { "enabled": true, "routeOnly": false, "destOverride": ["http", "tls"] } }
          ],
          "outbounds": [ { "tag": "proxy", "protocol": "vless" } ]
        }
        """.trimIndent()

    private val withoutOwnBlock =
        """
        {
          "inbounds": [ { "tag": "socks", "port": 10808, "protocol": "socks" } ],
          "outbounds": [ { "tag": "proxy", "protocol": "vless" } ]
        }
        """.trimIndent()

    private fun typed(settings: TunnelSettings): String {
        val result = XrayConfigGenerator.generate(GOLDEN_PROFILE, settings)
        check(result is ConfigResult.Ok) { "expected ConfigResult.Ok, got $result" }
        return result.json
    }

    private fun socksSniffing(
        raw: String,
        settings: TunnelSettings,
        override: OverrideBlocks? = null,
    ): JsonElement? {
        val result = RawConfigComposer.compose(raw, settings, assetDir = "/data/geo", override = override)
        check(result is ComposeResult.Ok) { "expected Ok, was $result" }
        val out = Json.parseToJsonElement(result.json) as JsonObject
        val socks = (out["inbounds"] as JsonArray)[0] as JsonObject
        return socks["sniffing"]
    }

    private fun JsonElement?.destOverride(): List<String> =
        (((this as JsonObject)["destOverride"]) as JsonArray).map { it.jsonPrimitive.content }

    @Test
    fun `typed off emits no sniffing block`() {
        typed(settings.copy(enableSniffing = false)) shouldNotContain "\"sniffing\""
    }

    @Test
    fun `typed off with a fakedns plan keeps only the fakedns override`() {
        val json = typed(settings.copy(enableSniffing = false, dns = fakeDnsPlan))

        json shouldContain
            """
            |      "sniffing": {
            |        "enabled": true,
            |        "destOverride": ["fakedns"]
            |      }
            """.trimMargin()
    }

    @Test
    fun `typed on with a fakedns plan is unchanged`() {
        typed(settings.copy(dns = fakeDnsPlan)) shouldContain """"destOverride": ["http", "tls", "quic", "fakedns"]"""
    }

    @Test
    fun `override branch off emits no sniffing block`() {
        val off = settings.copy(enableSniffing = false)
        val blocks = XrayConfigGenerator.overrideBlocks(off, OverrideTarget.ViaOutbound("proxy"))

        socksSniffing(withOwnBlock, off, blocks) shouldBe null
    }

    @Test
    fun `override branch off with a fakedns plan keeps only the fakedns override`() {
        val off = settings.copy(enableSniffing = false, dns = fakeDnsPlan)
        val blocks = XrayConfigGenerator.overrideBlocks(off, OverrideTarget.ViaOutbound("proxy"))

        socksSniffing(withOwnBlock, off, blocks).destOverride() shouldBe listOf("fakedns")
    }

    @Test
    fun `raw with its own block keeps it when a provider turns sniffing off`() {
        val providerOff = settings.copy(enableSniffing = false, sniffingReplacesOwn = false)

        socksSniffing(withOwnBlock, providerOff) shouldBe
            Json.parseToJsonElement("""{ "enabled": true, "routeOnly": false, "destOverride": ["http", "tls"] }""")
    }

    @Test
    fun `raw with its own block loses it when the user turns sniffing off`() {
        val userOff = settings.copy(enableSniffing = false, sniffingReplacesOwn = true)

        socksSniffing(withOwnBlock, userOff) shouldBe null
    }

    @Test
    fun `raw with its own block keeps only fakedns when the user turns sniffing off under a fakedns plan`() {
        val userOff = settings.copy(enableSniffing = false, sniffingReplacesOwn = true, dns = fakeDnsPlan)

        socksSniffing(withOwnBlock, userOff).destOverride() shouldBe listOf("fakedns")
    }

    @Test
    fun `raw with its own block takes the app default when the user turns sniffing on`() {
        val userOn = settings.copy(enableSniffing = true, sniffingReplacesOwn = true)

        socksSniffing(withOwnBlock, userOn).destOverride() shouldBe listOf("http", "tls", "quic")
    }

    @Test
    fun `raw without its own block follows a provider's off`() {
        val providerOff = settings.copy(enableSniffing = false, sniffingReplacesOwn = false)

        socksSniffing(withoutOwnBlock, providerOff) shouldBe null
    }

    @Test
    fun `raw without its own block keeps today's default when on`() {
        socksSniffing(withoutOwnBlock, settings).destOverride() shouldBe listOf("http", "tls", "quic")
    }

    private val withOwnFakeDnsBlock =
        """
        {
          "inbounds": [
            { "tag": "socks", "port": 10808, "protocol": "socks",
              "sniffing": { "enabled": true, "destOverride": ["http", "tls", "fakedns"] } }
          ],
          "outbounds": [ { "tag": "proxy", "protocol": "vless" } ]
        }
        """.trimIndent()

    @Test
    fun `raw with its own fakedns block keeps fakedns when the user turns sniffing off`() {
        // The config's own FakeDNS needs that override to map fake addresses back to domains.
        val userOff = settings.copy(enableSniffing = false, sniffingReplacesOwn = true)

        socksSniffing(withOwnFakeDnsBlock, userOff) shouldBe
            Json.parseToJsonElement("""{ "enabled": true, "destOverride": ["fakedns"] }""")
    }

    @Test
    fun `raw with its own fakedns block keeps fakedns when the user turns sniffing on`() {
        val userOn = settings.copy(enableSniffing = true, sniffingReplacesOwn = true)

        socksSniffing(withOwnFakeDnsBlock, userOn).destOverride() shouldBe listOf("http", "tls", "quic", "fakedns")
    }
}
