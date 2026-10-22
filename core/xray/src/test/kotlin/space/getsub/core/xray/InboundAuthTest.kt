// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import space.getsub.core.model.ProxyCredentials

/**
 * Both loopback inbounds require the session's username and password (M9-E spec §5.3).
 *
 * The SOCKS half is the one that fails silently: Xray-core v26.10.10 ignores `accounts` unless
 * `auth` is exactly `"password"` (`infra/conf/socks.go:41-48`) and the inbound stays open, so
 * every assertion below checks the `auth` key alongside the accounts. The HTTP inbound has no
 * `auth` key; accounts alone enforce it (`infra/conf/http.go`, `proxy/http/server.go:121`).
 */
class InboundAuthTest {
    private val creds = ProxyCredentials("u1", "p1")

    private val settings =
        TunnelSettings(
            socksPort = 41080,
            dnsServer = "1.1.1.1",
            enableSniffing = true,
            proxyCredentials = creds,
            httpPort = 41081,
        )

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun JsonObject.settingsBlock(): JsonObject = getValue("settings").jsonObject

    private fun JsonObject.accounts(): List<Pair<String, String>> =
        getValue("accounts").jsonArray.map {
            val account = it.jsonObject
            account.getValue("user").jsonPrimitive.content to account.getValue("pass").jsonPrimitive.content
        }

    private fun assertSocksAuthenticated(
        inbound: JsonObject,
        expected: ProxyCredentials,
    ) {
        val settings = inbound.settingsBlock()
        settings["auth"] shouldBe JsonPrimitive("password")
        settings.accounts() shouldBe listOf(expected.user to expected.pass)
        settings["udp"] shouldBe JsonPrimitive(true)
    }

    private fun assertHttpAuthenticated(
        inbound: JsonObject,
        expected: ProxyCredentials,
    ) {
        val settings = inbound.settingsBlock()
        settings.keys shouldBe setOf("accounts")
        settings.accounts() shouldBe listOf(expected.user to expected.pass)
    }

    @Test
    fun `the socks inbound requires password auth with the given account`() {
        assertSocksAuthenticated(parse(socksInboundJson(41080, DEFAULT_SNIFFING, creds)), creds)
    }

    @Test
    fun `the socks inbound with preserved sniffing requires password auth too`() {
        val sniffing = parse("""{"enabled": true, "destOverride": ["tls"]}""")
        assertSocksAuthenticated(
            parse(socksInboundJsonPreservingSniffing(41080, sniffing, creds, "client-socks")),
            creds,
        )
    }

    @Test
    fun `the socks inbound without sniffing still requires password auth`() {
        assertSocksAuthenticated(parse(socksInboundJson(41080, null, creds)), creds)
    }

    @Test
    fun `the http inbound requires the given account`() {
        assertHttpAuthenticated(parse(httpInboundJson(41081, creds)), creds)
    }

    @Test
    fun `typed configs authenticate both inbounds`() {
        val json = (XrayConfigGenerator.generate(GOLDEN_PROFILE, settings) as ConfigResult.Ok).json
        val inbounds = parse(json).getValue("inbounds").jsonArray
        inbounds.size shouldBe 2
        assertSocksAuthenticated(inbounds[0].jsonObject, creds)
        assertHttpAuthenticated(inbounds[1].jsonObject, creds)
    }

    @Test
    fun `composed raw configs authenticate both inbounds`() {
        val raw =
            """
            {
              "inbounds": [ { "tag": "socks", "port": 10808, "protocol": "socks", "settings": { "udp": true } } ],
              "outbounds": [ { "tag": "proxy", "protocol": "freedom" } ]
            }
            """.trimIndent()
        val json = (RawConfigComposer.compose(raw, settings, "/data/geo", override = null) as ComposeResult.Ok).json
        val inbounds = parse(json).getValue("inbounds") as JsonArray
        inbounds.size shouldBe 2
        assertSocksAuthenticated(inbounds[0].jsonObject, creds)
        assertHttpAuthenticated(inbounds[1].jsonObject, creds)
    }

    @Test
    fun `manual credentials with JSON metacharacters survive escaping`() {
        // Manual credentials are whatever the user typed; Auto ones are alphanumeric.
        val odd = ProxyCredentials("us\"er\\", "pa:ss\n\u0001")
        assertSocksAuthenticated(parse(socksInboundJson(41080, DEFAULT_SNIFFING, odd)), odd)
        assertHttpAuthenticated(parse(httpInboundJson(41081, odd)), odd)
    }

    @Test
    fun `the same credentials give byte-identical inbounds`() {
        socksInboundJson(41080, DEFAULT_SNIFFING, creds) shouldBe socksInboundJson(41080, DEFAULT_SNIFFING, creds)
        httpInboundJson(41081, creds) shouldBe httpInboundJson(41081, creds)
    }
}
