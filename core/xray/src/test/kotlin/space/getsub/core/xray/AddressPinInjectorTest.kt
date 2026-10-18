// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class AddressPinInjectorTest {
    private val pin = ServerAddressPin("srv.example", "203.0.113.5")

    private fun outbounds(json: String) = Json.parseToJsonElement(json).jsonArray

    private fun JsonArray.at(vararg path: Any): JsonElement =
        path.fold(this as JsonElement) { e, k -> if (k is Int) e.jsonArray[k] else e.jsonObject[k as String]!! }

    private fun JsonArray.has(vararg path: Any): Boolean =
        runCatching { at(*path) }.isSuccess

    private fun pinned(stream: String): JsonArray =
        AddressPinInjector.apply(
            outbounds("""[{"settings":{"vnext":[{"address":"srv.example"}]},"streamSettings":$stream}]"""),
            pin,
        )

    @Test
    fun vnextAddressIsSwappedAndTlsSniFilled() {
        val out =
            AddressPinInjector.apply(
                outbounds(
                    """[{"protocol":"vless","settings":{"vnext":[{"address":"srv.example","port":443}]},
            "streamSettings":{"network":"tcp","security":"tls","tlsSettings":{}}}]""",
                ),
                pin,
            )
        out.at(0, "settings", "vnext", 0, "address").jsonPrimitive.content shouldBe "203.0.113.5"
        out.at(0, "streamSettings", "tlsSettings", "serverName").jsonPrimitive.content shouldBe "srv.example"
    }

    @Test
    fun tlsSettingsAreCreatedWhenMissing() {
        val out = pinned("""{"security":"tls"}""")
        out.at(0, "streamSettings", "tlsSettings", "serverName").jsonPrimitive.content shouldBe "srv.example"
    }

    @Test
    fun serversArrayIsSwapped() {
        val out =
            AddressPinInjector.apply(
                outbounds("""[{"protocol":"trojan","settings":{"servers":[{"address":"srv.example","port":443}]}}]"""),
                pin,
            )
        out.at(0, "settings", "servers", 0, "address").jsonPrimitive.content shouldBe "203.0.113.5"
    }

    @Test
    fun caseAndTrailingDotMatch() {
        val out = AddressPinInjector.apply(outbounds("""[{"settings":{"vnext":[{"address":"SRV.Example."}]}}]"""), pin)
        out.at(0, "settings", "vnext", 0, "address").jsonPrimitive.content shouldBe "203.0.113.5"
    }

    @Test
    fun otherAddressesAreUntouched() {
        val input = outbounds("""[{"settings":{"vnext":[{"address":"other.example"}]}}]""")
        val out = AddressPinInjector.apply(input, pin)
        out shouldBe input
        (out[0] === input[0]) shouldBe true
    }

    @Test
    fun explicitServerNameAndRealityAreKept() {
        val tls = pinned("""{"security":"tls","tlsSettings":{"serverName":"cdn.example"}}""")
        tls.at(0, "streamSettings", "tlsSettings", "serverName").jsonPrimitive.content shouldBe "cdn.example"
        val reality = pinned("""{"security":"reality","realitySettings":{"serverName":"www.microsoft.com"}}""")
        reality.at(0, "streamSettings", "realitySettings", "serverName").jsonPrimitive.content shouldBe
            "www.microsoft.com"
        reality.has("streamSettings", "tlsSettings") shouldBe false
    }

    @Test
    fun blankRealityServerNameGetsTheDomain() {
        val out = pinned("""{"security":"reality","realitySettings":{"serverName":"","publicKey":"k"}}""")
        out.at(0, "streamSettings", "realitySettings", "serverName").jsonPrimitive.content shouldBe "srv.example"
        out.at(0, "streamSettings", "realitySettings", "publicKey").jsonPrimitive.content shouldBe "k"
    }

    @Test
    fun wsHostFilledOnlyWhenNoHostAnywhere() {
        val bare = pinned("""{"network":"ws","wsSettings":{"path":"/x"}}""")
        bare.at(0, "streamSettings", "wsSettings", "host").jsonPrimitive.content shouldBe "srv.example"
        val withHeader = pinned("""{"network":"ws","wsSettings":{"path":"/x","headers":{"host":"cdn.example"}}}""")
        withHeader.has("streamSettings", "wsSettings", "host") shouldBe false
        val withHost = pinned("""{"network":"ws","wsSettings":{"host":"cdn.example"}}""")
        withHost.at(0, "streamSettings", "wsSettings", "host").jsonPrimitive.content shouldBe "cdn.example"
    }

    @Test
    fun wsOverTlsGetsNoHost() {
        val out = pinned("""{"network":"ws","security":"tls","wsSettings":{"path":"/x"}}""")
        out.has("streamSettings", "wsSettings", "host") shouldBe false
        out.at(0, "streamSettings", "tlsSettings", "serverName").jsonPrimitive.content shouldBe "srv.example"
    }

    @Test
    fun httpupgradeNoneGetsHost() {
        val out = pinned("""{"network":"httpupgrade"}""")
        out.at(0, "streamSettings", "httpupgradeSettings", "host").jsonPrimitive.content shouldBe "srv.example"
    }

    @Test
    fun grpcAuthorityOnlyWithoutSecurity() {
        val none = pinned("""{"network":"grpc","grpcSettings":{"serviceName":"s"}}""")
        none.at(0, "streamSettings", "grpcSettings", "authority").jsonPrimitive.content shouldBe "srv.example"
        val tls = pinned("""{"network":"grpc","security":"tls","grpcSettings":{"serviceName":"s"}}""")
        tls.has("streamSettings", "grpcSettings", "authority") shouldBe false
    }

    @Test
    fun xhttpHostOnlyWithoutSecurity() {
        val none = pinned("""{"network":"xhttp"}""")
        none.at(0, "streamSettings", "xhttpSettings", "host").jsonPrimitive.content shouldBe "srv.example"
        val reality =
            pinned(
                """{"network":"xhttp","security":"reality","realitySettings":{"serverName":"x.example"}}""",
            )
        reality.has("streamSettings", "xhttpSettings") shouldBe false
    }

    @Test
    fun legacySplithttpUsesItsOwnSettingsKey() {
        val out = pinned("""{"network":"splithttp"}""")
        out.at(0, "streamSettings", "splithttpSettings", "host").jsonPrimitive.content shouldBe "srv.example"
        out.has("streamSettings", "xhttpSettings") shouldBe false
    }

    @Test
    fun matchesFindsTheDomainOrNot() {
        AddressPinInjector.matches(
            """{"outbounds":[{"settings":{"vnext":[{"address":"srv.example"}]}}]}""",
            "srv.example",
        ) shouldBe true
        AddressPinInjector.matches(
            """{"outbounds":[{"settings":{"servers":[{"address":"SRV.example."}]}}]}""",
            "srv.example",
        ) shouldBe true
        AddressPinInjector.matches(
            """{"outbounds":[{"settings":{"vnext":[{"address":"x.example"}]}}]}""",
            "srv.example",
        ) shouldBe false
        AddressPinInjector.matches("""{"inbounds":[]}""", "srv.example") shouldBe false
        AddressPinInjector.matches("not json", "srv.example") shouldBe false
    }

    @Test
    fun hysteriaFlatSettingsAddressIsSwappedAndServerNameFilled() {
        val raw =
            """[{"protocol":"hysteria","settings":{"version":2,"address":"SRV.example","port":443},
            "streamSettings":{"network":"hysteria","security":"tls","tlsSettings":{"serverName":""}}}]"""
        val out = AddressPinInjector.apply(outbounds(raw), pin)
        out.at(0, "settings", "address").jsonPrimitive.content shouldBe "203.0.113.5"
        out.at(0, "settings", "version").jsonPrimitive.content shouldBe "2"
        out.at(0, "streamSettings", "tlsSettings", "serverName").jsonPrimitive.content shouldBe "srv.example"
        AddressPinInjector.matches("""{"outbounds":$raw}""", "srv.example") shouldBe true
    }

    @Test
    fun hysteriaFlatSettingsAddressTakesAnIpv6Pin() {
        val v6 = ServerAddressPin("srv.example", "2001:db8::5")
        val raw =
            """[{"protocol":"hysteria","settings":{"version":2,"address":"srv.example","port":443},
            "streamSettings":{"network":"hysteria","security":"tls"}}]"""
        val out = AddressPinInjector.apply(outbounds(raw), v6)
        out.at(0, "settings", "address").jsonPrimitive.content shouldBe "2001:db8::5"
        out.at(0, "streamSettings", "tlsSettings", "serverName").jsonPrimitive.content shouldBe "srv.example"
    }

    @Test
    fun flatAddressOnANonServerProtocolIsUntouched() {
        val raw = """[{"protocol":"dns","settings":{"address":"srv.example","port":53}}]"""
        val input = outbounds(raw)
        (AddressPinInjector.apply(input, pin)[0] === input[0]) shouldBe true
        AddressPinInjector.matches("""{"outbounds":$raw}""", "srv.example") shouldBe false
    }

    @Test
    fun nullPinIsIdentity() {
        val i = outbounds("""[{"settings":{}}]""")
        AddressPinInjector.apply(i, null) shouldBe i
    }

    @Test
    fun composeAppliesThePin() {
        val raw =
            """{"outbounds":[{"tag":"proxy","protocol":"vless",
            "settings":{"vnext":[{"address":"srv.example","port":443}]},
            "streamSettings":{"security":"tls"}}]}"""
        val settings =
            TunnelSettings(
                socksPort = 41080,
                dnsServer = "1.1.1.1",
                enableSniffing = true,
                proxyCredentials = TEST_CREDENTIALS,
                httpPort = 41081,
            )
                .copy(addressPin = pin)
        val result = RawConfigComposer.compose(raw, settings, assetDir = "/data/geo", override = null)
        val json = (result as ComposeResult.Ok).json
        json shouldContain "203.0.113.5"
        json shouldContain "srv.example"
        json shouldNotContain "\"address\":\"srv.example\""
    }

    // ── M9-D2 research §5.2: WireGuard's only dial target is peers[].endpoint ──

    private fun wireguard(endpoint: String) =
        outbounds(
            """[{"tag":"wg","protocol":"wireguard","settings":{"secretKey":"k","peers":[
                {"publicKey":"p","endpoint":"$endpoint"},{"publicKey":"q","endpoint":"other.example:1"}]}}]""",
        )

    @Test
    fun wireguardEndpointHostIsSwappedAndThePortKept() {
        val out = AddressPinInjector.apply(wireguard("SRV.example.:51820"), pin)
        out.at(0, "settings", "peers", 0, "endpoint").jsonPrimitive.content shouldBe "203.0.113.5:51820"
        out.at(0, "settings", "peers", 1, "endpoint").jsonPrimitive.content shouldBe "other.example:1"
        // No TLS and no transport: nothing to fill, so no streamSettings appears.
        out.has(0, "streamSettings") shouldBe false
    }

    @Test
    fun wireguardEndpointGetsABracketedIpv6Pin() {
        val v6 = ServerAddressPin("srv.example", "2001:db8::5")
        val out = AddressPinInjector.apply(wireguard("srv.example:51820"), v6)
        out.at(0, "settings", "peers", 0, "endpoint").jsonPrimitive.content shouldBe "[2001:db8::5]:51820"
    }

    @Test
    fun wireguardEndpointNamingAnotherHostOrUnreadableIsLeftAlone() {
        for (endpoint in listOf("other.example:51820", "[2001:db8::1]:51820", "srv.example", "srv.example:")) {
            val array = wireguard(endpoint)
            AddressPinInjector.apply(array, pin) shouldBe array
            AddressPinInjector.matches("""{"outbounds":$array}""", "srv.example") shouldBe false
        }
    }

    @Test
    fun matchesFindsAWireguardEndpointAndAnHttpFlatAddress() {
        AddressPinInjector.matches("""{"outbounds":${wireguard("srv.example:51820")}}""", "srv.example") shouldBe true
        val http = """[{"protocol":"http","settings":{"address":"srv.example","port":8080}}]"""
        AddressPinInjector.matches("""{"outbounds":$http}""", "srv.example") shouldBe true
        val out = AddressPinInjector.apply(outbounds(http), pin)
        out.at(0, "settings", "address").jsonPrimitive.content shouldBe "203.0.113.5"
    }
}
