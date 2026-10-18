// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import space.getsub.core.model.Applied
import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.NoiseItem
import space.getsub.core.model.NoisePayload
import space.getsub.core.model.NoiseSpec
import space.getsub.core.model.Obfuscation
import space.getsub.core.model.Outbound
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.XrayRange
import space.getsub.core.model.refusedWithoutTransportSecurity

/**
 * M9-D2a spec §1 and §2: the typed VMess, Trojan, Shadowsocks and SOCKS outbounds.
 *
 * The goldens are hand-written from the spec §1 table and checked against Xray-core v26.10.10's
 * `infra/conf/{vmess,trojan,shadowsocks,socks}.go` structs, not captured from this generator.
 */
class XrayConfigGeneratorProtocolsTest {
    private val settings = TunnelSettings(socksPort = 10808, dnsServer = "1.1.1.1", enableSniffing = true)
    private val pin = ServerAddressPin(domain = "srv.example", ip = "203.0.113.5")

    private fun generate(
        outbound: Outbound,
        tunnel: TunnelSettings = settings,
    ): ConfigResult = XrayConfigGenerator.generate(Profile(id = "id", name = "n", outbound = outbound), tunnel)

    private fun json(
        outbound: Outbound,
        tunnel: TunnelSettings = settings,
    ): String {
        val result = generate(outbound, tunnel)
        // The result's protocol name only; never the outbound, which carries secrets.
        check(result is ConfigResult.Ok) { "expected ConfigResult.Ok" }
        return result.json
    }

    private fun golden(name: String) = checkNotNull(javaClass.getResource("/golden/$name")).readText().trimEnd()

    private fun proxy(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject.getValue("outbounds").jsonArray[0].jsonObject

    private fun JsonObject.server(listKey: String): JsonObject =
        getValue("settings").jsonObject.getValue(listKey).jsonArray.single().jsonObject

    private fun streamText(json: String): String =
        json.substringAfter("\"streamSettings\": {").substringBefore("\n      }")

    private val allFour: List<Outbound> = listOf(GOLDEN_VMESS, GOLDEN_TROJAN, GOLDEN_SS, GOLDEN_SOCKS)

    // ── Goldens ─────────────────────────────────────────────────────────────

    @Test fun `vmess ws tls matches its golden`() {
        json(GOLDEN_VMESS) shouldBe golden("vmess-ws-tls.json")
    }

    @Test fun `trojan tcp tls matches its golden`() {
        json(GOLDEN_TROJAN) shouldBe golden("trojan-tcp-tls.json")
    }

    @Test fun `shadowsocks aes-256-gcm matches its golden`() {
        json(GOLDEN_SS) shouldBe golden("shadowsocks-aes-256-gcm.json")
    }

    @Test fun `shadowsocks 2022 matches its golden`() {
        json(GOLDEN_SS2022) shouldBe golden("shadowsocks-2022.json")
    }

    @Test fun `socks with credentials matches its golden`() {
        json(GOLDEN_SOCKS) shouldBe golden("socks-auth.json")
    }

    @Test fun `socks without credentials matches its golden`() {
        json(GOLDEN_SOCKS.copy(username = null, password = null)) shouldBe golden("socks-noauth.json")
    }

    // ── Exact key sets (M9-D2a spec §1) ─────────────────────────────────────

    @Test fun `vmess user carries exactly id and security`() {
        val user = proxy(json(GOLDEN_VMESS)).server("vnext").getValue("users").jsonArray.single().jsonObject
        user.keys shouldBe setOf("id", "security")
    }

    @Test fun `trojan server carries exactly address, port and password`() {
        proxy(json(GOLDEN_TROJAN)).server("servers").keys shouldBe setOf("address", "port", "password")
    }

    @Test fun `shadowsocks server carries exactly address, port, method and password`() {
        proxy(json(GOLDEN_SS)).server("servers").keys shouldBe setOf("address", "port", "method", "password")
    }

    @Test fun `socks with a username carries one user and pass`() {
        val user = proxy(json(GOLDEN_SOCKS)).server("servers").getValue("users").jsonArray.single().jsonObject
        user shouldBe JsonObject(mapOf("user" to JsonPrimitive("socks-user"), "pass" to JsonPrimitive("socks-pass")))
    }

    @Test fun `socks with a blank or null username carries no users, password or not`() {
        listOf(
            GOLDEN_SOCKS.copy(username = null),
            GOLDEN_SOCKS.copy(username = ""),
            GOLDEN_SOCKS.copy(username = "  "),
            GOLDEN_SOCKS.copy(username = null, password = null),
        ).forEachIndexed { index, socks ->
            withClue("case $index") {
                proxy(json(socks)).server("servers").keys shouldBe setOf("address", "port")
            }
        }
    }

    @Test fun `socks with a username and no password sends an empty pass`() {
        val user =
            proxy(json(GOLDEN_SOCKS.copy(password = null)))
                .server("servers")
                .getValue("users")
                .jsonArray
                .single()
                .jsonObject
        user.getValue("pass").jsonPrimitive.content shouldBe ""
    }

    // ── Keys the core no longer has ─────────────────────────────────────────

    @Test fun `never emits allowInsecure, alterId, experiments or a trojan flow`() {
        val insecure = GOLDEN_TLS.copy(allowInsecure = true)
        val outbounds =
            listOf(
                GOLDEN_VMESS.copy(alterId = 64, stream = GOLDEN_VMESS.stream.copy(security = insecure)),
                GOLDEN_TROJAN.copy(stream = GOLDEN_TROJAN.stream.copy(security = insecure)),
                GOLDEN_SS,
                GOLDEN_SS2022,
                GOLDEN_SOCKS,
            )
        outbounds.forEachIndexed { index, outbound ->
            withClue("case $index") {
                val text = json(outbound)
                text shouldNotContain "allowInsecure"
                text shouldNotContain "alterId"
                text shouldNotContain "experiments"
                text shouldNotContain "\"flow\""
            }
        }
    }

    // ── Stream settings (M9-D2a spec §2) ────────────────────────────────────

    @Test fun `vmess and trojan share the vless streamSettings text`() {
        val streams =
            listOf(
                StreamSettings("ws", GOLDEN_TLS, TransportOptions.WebSocket("/ws", mapOf("Host" to "cdn.example"))),
                StreamSettings("grpc", GOLDEN_REALITY, TransportOptions.Grpc("svc")),
                StreamSettings("xhttp", Security.None, TransportOptions.Xhttp("/x", null, "auto")),
            )
        streams.forEach { stream ->
            withClue(stream.network) {
                listOf(settings, settings.copy(addressPin = pin)).forEach { tunnel ->
                    val vless = streamText(json(GOLDEN_OUTBOUND.copy(stream = stream), tunnel))
                    streamText(json(GOLDEN_VMESS.copy(stream = stream), tunnel)) shouldBe vless
                    streamText(json(GOLDEN_TROJAN.copy(stream = stream), tunnel)) shouldBe vless
                }
            }
        }
    }

    @Test fun `shadowsocks and socks get plain tcp streamSettings`() {
        listOf(GOLDEN_SS, GOLDEN_SOCKS).forEach { outbound ->
            val stream = proxy(json(outbound)).getValue("streamSettings").jsonObject
            stream shouldBe JsonObject(mapOf("network" to JsonPrimitive("tcp"), "security" to JsonPrimitive("none")))
        }
    }

    @Test fun `shadowsocks and socks carry the fragment but no noise`() {
        val fragment =
            Applied(
                FragmentSpec(FragmentPackets.TlsHello, XrayRange(50, 100), XrayRange(10, 20), XrayRange(10, 10)),
                replaceOwn = false,
            )
        val noise =
            Applied(
                NoiseSpec(
                    listOf(NoiseItem(NoisePayload.Random(XrayRange(10, 20), XrayRange(0, 255)), XrayRange(10, 16))),
                ),
                replaceOwn = false,
            )
        val masked = settings.copy(obfuscation = Obfuscation(fragment, noise))
        val vlessMask = proxy(json(GOLDEN_OUTBOUND, masked)).getValue("streamSettings").jsonObject.getValue("finalmask")
        listOf(GOLDEN_SS, GOLDEN_SOCKS).forEach { outbound ->
            val stream = proxy(json(outbound, masked)).getValue("streamSettings").jsonObject
            stream.keys shouldBe setOf("network", "finalmask", "security")
            stream.getValue("finalmask") shouldBe vlessMask
            stream.getValue("finalmask").jsonObject.keys shouldBe setOf("tcp")
        }
    }

    // ── Address pin (M9-C spec §3.2) ────────────────────────────────────────

    @Test fun `emits the pin ip as the address for all four`() {
        val pinned = settings.copy(addressPin = pin)
        allFour.forEach { outbound ->
            val listKey = if (outbound === GOLDEN_VMESS) "vnext" else "servers"
            withClue(listKey) {
                val text = json(outbound, pinned)
                proxy(text).server(listKey).getValue("address").jsonPrimitive.content shouldBe pin.ip
                text shouldNotContain "\"address\": \"example.com\""
            }
        }
    }

    @Test fun `a blank tls serverName falls back to the pin domain for vmess and trojan`() {
        val pinned = settings.copy(addressPin = pin)
        val blank = GOLDEN_TLS.copy(serverName = "")
        listOf(
            GOLDEN_VMESS.copy(stream = GOLDEN_VMESS.stream.copy(security = blank)),
            GOLDEN_TROJAN.copy(stream = GOLDEN_TROJAN.stream.copy(security = blank)),
        ).forEach { outbound ->
            val tls = proxy(json(outbound, pinned)).getValue("streamSettings").jsonObject.getValue("tlsSettings")
            tls.jsonObject.getValue("serverName").jsonPrimitive.content shouldBe pin.domain
        }
    }

    /**
     * Plan Review Focus 4: the core judges the emitted (pinned, private) address, but
     * `refusedWithoutTransportSecurity` judges the stored domain, so this row stays refused. Pinned
     * here so that loosening it is a deliberate change.
     */
    @Test fun `plaintext trojan to a public domain stays refused even when pinned to a private ip`() {
        val trojan = GOLDEN_TROJAN.copy(stream = StreamSettings("tcp", Security.None))
        val pinned = settings.copy(addressPin = ServerAddressPin(domain = "example.com", ip = "10.0.0.1"))

        trojan.refusedWithoutTransportSecurity() shouldBe true
        // The generator itself still emits; the refusal is the caller's (StoredProfile.connectable).
        json(trojan, pinned) shouldContain "\"address\": \"10.0.0.1\""
    }

    // ── Shadowsocks refusals (M9-D2a spec §1) ───────────────────────────────

    @Test fun `a cipher the core dropped is refused by name`() {
        listOf("aes-192-gcm", "none", "plain").forEach { method ->
            withClue(method) {
                val result = generate(GOLDEN_SS.copy(method = method))
                result.shouldBeInstanceOf<ConfigResult.Unsupported>()
                result.protocol shouldBe "Shadowsocks cipher"
            }
        }
    }

    @Test fun `a 2022 key of the wrong length is refused without quoting it`() {
        val shortKey = "AAECAwQFBgcICQoL" // 12 bytes; aes-128 needs 16
        val result = generate(GOLDEN_SS2022.copy(password = shortKey))
        result.shouldBeInstanceOf<ConfigResult.Unsupported>()
        result.protocol shouldBe "Shadowsocks 2022 key"
        result.toString() shouldNotContain shortKey
    }

    @Test fun `every generated protocol is deterministic`() {
        (allFour + GOLDEN_SS2022).forEach { outbound ->
            json(outbound) shouldBe json(outbound)
        }
    }
}
