// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import space.getsub.core.model.DnsResolver
import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.Outbound
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.VlessOutbound

/**
 * Ruling D2-R16: the HTTP proxy outbound is CONNECT-only (M9-D2 research §2.2), so a plain-DNS (DoU) resolver
 * routed through it never answers. Such a server is written `tcp://ip:53`, which Xray v26.10.10 dials through
 * routing like DoU (`app/dns/nameserver.go:67`, `nameserver_tcp.go:44`), as a TCP destination on the same IP.
 */
class DnsOverTcpTest {
    private val http = HttpOutbound("proxy.example", 8080, null, null, Security.None)
    private val vless =
        VlessOutbound(
            "v.example",
            443,
            "70cc48c5-b2f4-4a1e-9f3d-0123456789ab",
            null,
            StreamSettings("tcp", Security.Tls("v.example", "chrome", false)),
        )

    private val split =
        DnsPlan(
            servers =
            listOf(
                DnsServerSpec("8.8.8.8", domains = listOf("geosite:cn"), skipFallback = true),
                DnsServerSpec("1.1.1.1"),
                DnsServerSpec("2606:4700:4700::1111"),
                DnsServerSpec("https://dns.example/dns-query"),
            ),
            hosts = mapOf("dns.example" to "9.9.9.9"),
            fakeDns = false,
            directMatch = "8.8.8.8",
            proxyMatch = "1.1.1.1",
        )

    private fun config(
        outbound: Outbound,
        plan: DnsPlan?,
    ): JsonObject {
        val settings =
            TunnelSettings(
                socksPort = 10808,
                dnsServer = "1.1.1.1",
                enableSniffing = true,
                proxyCredentials = TEST_CREDENTIALS,
                dns = plan,
            )
        val result = XrayConfigGenerator.generate(Profile(id = "id", name = "n", outbound = outbound), settings)
        check(result is ConfigResult.Ok) { "expected ConfigResult.Ok" }
        return Json.parseToJsonElement(result.json).jsonObject
    }

    private fun JsonObject.servers(): List<JsonElement> = getValue("dns").jsonObject.getValue("servers").jsonArray

    private fun JsonElement.address(): String =
        when (this) {
            is JsonPrimitive -> content
            is JsonObject -> getValue("address").jsonPrimitive.content
            is JsonArray -> error("not a server")
        }

    @Test
    fun `an http proxy sends every proxy-routed plain resolver over tcp, v6 bracketed`() {
        val addresses = config(http, split).servers().map { it.address() }
        addresses shouldBe
            listOf("8.8.8.8", "tcp://1.1.1.1:53", "tcp://[2606:4700:4700::1111]:53", "https://dns.example/dns-query")
    }

    @Test
    fun `the domestic direct server keeps its scope, and the proxy resolver rule still matches its ip`() {
        val c = config(http, split)
        val domestic = c.servers()[0].jsonObject
        domestic.getValue("address").jsonPrimitive.content shouldBe "8.8.8.8"
        domestic.getValue("skipFallback").jsonPrimitive.content shouldBe "true"
        val rules = c.getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.toString() }
        rules shouldContain """{"type":"field","inboundTag":["dns-module"],"ip":["1.1.1.1"],"outboundTag":"proxy"}"""
    }

    @Test
    fun `an unscoped plain resolver rides the proxy catch-all and is rewritten too`() {
        val only = DnsPlan(listOf(DnsServerSpec("9.9.9.9")), emptyMap(), false, directMatch = null, proxyMatch = null)
        config(http, only).servers().map { it.address() } shouldBe listOf("tcp://9.9.9.9:53")
    }

    @Test
    fun `an outbound that carries udp keeps plain dns`() {
        config(vless, split).servers().map { it.address() } shouldBe
            listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "https://dns.example/dns-query")
    }

    @Test
    fun `the planner never returns no plan for an outbound that cannot carry udp`() {
        // With no plan there is no port-53 hijack, so an app's UDP query would meet the proxy outbound directly.
        DnsPlanner.plan(null, DnsResolver.DEFAULT, null, sniffingEnabled = true) shouldBe null
        val plan = DnsPlanner.plan(null, DnsResolver.DEFAULT, null, sniffingEnabled = true, outboundCarriesUdp = false)
        plan shouldNotBe null
        val c = config(http, plan)
        c.servers().map { it.address() } shouldBe listOf("tcp://1.1.1.1:53")
        c.toString() shouldContain """"outboundTag":"dns-out""""
    }
}
