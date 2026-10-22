// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import space.getsub.core.model.Profile
import space.getsub.core.model.Security

class AddressPinGeneratorTest {
    private val pin = ServerAddressPin(domain = "srv.example", ip = "203.0.113.5")

    private fun raw(
        p: Profile,
        pin: ServerAddressPin?,
    ): String {
        val r =
            XrayConfigGenerator.generate(
                p,
                TunnelSettings(
                    socksPort = 10808,
                    dnsServer = "1.1.1.1",
                    enableSniffing = true,
                    proxyCredentials = TEST_CREDENTIALS,
                    addressPin = pin,
                ),
            )
        check(r is ConfigResult.Ok) { "expected Ok, got $r" }
        return r.json
    }

    private fun json(
        p: Profile,
        pin: ServerAddressPin?,
    ): JsonObject = Json.parseToJsonElement(raw(p, pin)).jsonObject

    private fun golden(name: String): String = checkNotNull(javaClass.getResource("/address-pin/$name.json")).readText()

    private fun JsonObject.outbound(): JsonObject = getValue("outbounds").jsonArray[0].jsonObject

    private fun JsonObject.stream(): JsonObject = outbound().getValue("streamSettings").jsonObject

    private fun JsonObject.vnextAddress(): String =
        outbound().getValue("settings").jsonObject.getValue("vnext").jsonArray[0]
            .jsonObject.getValue("address").jsonPrimitive.content

    private fun JsonObject.field(
        block: String,
        key: String,
    ): String? = stream()[block]?.jsonObject?.get(key)?.jsonPrimitive?.content

    private fun JsonObject.wsHeaders(): Map<String, String>? =
        stream()["wsSettings"]?.jsonObject?.get("headers")?.let { h: JsonElement ->
            h.jsonObject.mapValues { it.value.jsonPrimitive.content }
        }

    @Test
    fun noPinIsByteIdentical() {
        mapOf(
            "tls" to tlsProfile(),
            "reality" to realityProfile(),
            "ws" to wsProfile(emptyMap()),
            "xhttp" to xhttpProfile(null),
            "grpc" to grpcProfile(),
        ).forEach { (name, p) -> raw(p, pin = null) shouldBe golden(name) }
    }

    @Test
    fun pinReplacesTheVnextAddress() {
        json(tlsProfile(), pin).vnextAddress() shouldBe "203.0.113.5"
    }

    @Test
    fun explicitTlsServerNameIsKept() {
        json(tlsProfile("cdn.example"), pin).field("tlsSettings", "serverName") shouldBe "cdn.example"
    }

    @Test
    fun blankTlsServerNameGetsTheDomain() {
        json(tlsProfile(""), pin).field("tlsSettings", "serverName") shouldBe "srv.example"
    }

    @Test
    fun setRealityServerNameIsKept() {
        val name = json(realityProfile("www.microsoft.com"), pin).field("realitySettings", "serverName")
        name shouldBe "www.microsoft.com"
    }

    @Test
    fun blankRealityServerNameGetsTheDomain() {
        json(realityProfile(""), pin).field("realitySettings", "serverName") shouldBe "srv.example"
    }

    @Test
    fun wsNoneWithoutHostGetsHostHeader() {
        json(wsProfile(emptyMap()), pin).wsHeaders() shouldBe mapOf("Host" to "srv.example")
    }

    @Test
    fun wsRealityWithoutHostGetsHostHeader() {
        json(wsProfile(emptyMap(), realitySecurity()), pin).wsHeaders() shouldBe mapOf("Host" to "srv.example")
    }

    @Test
    fun wsTlsWithoutHostGetsNoHostHeader() {
        json(wsProfile(emptyMap(), tlsSecurity("")), pin).wsHeaders() shouldBe null
    }

    @Test
    fun wsWithHostKeepsIt() {
        json(wsProfile(mapOf("host" to "cdn.example")), pin).wsHeaders() shouldBe mapOf("host" to "cdn.example")
    }

    @Test
    fun xhttpNoneWithoutHostGetsTheDomain() {
        json(xhttpProfile(null), pin).field("xhttpSettings", "host") shouldBe "srv.example"
    }

    @Test
    fun xhttpTlsWithoutHostStaysWithoutHost() {
        json(xhttpProfile(null, tlsSecurity("")), pin).field("xhttpSettings", "host") shouldBe null
    }

    @Test
    fun xhttpExplicitHostIsKept() {
        json(xhttpProfile("cdn.example"), pin).field("xhttpSettings", "host") shouldBe "cdn.example"
    }

    @Test
    fun grpcNoneGetsAuthority() {
        json(grpcProfile(Security.None), pin).field("grpcSettings", "authority") shouldBe "srv.example"
    }

    @Test
    fun grpcTlsGetsNoAuthority() {
        json(grpcProfile(tlsSecurity("")), pin).field("grpcSettings", "authority") shouldBe null
    }

    @Test
    fun pinToStringIsRedacted() {
        pin.toString().contains("203.0.113.5") shouldBe false
        pin.toString().contains("srv.example") shouldBe false
    }
}
