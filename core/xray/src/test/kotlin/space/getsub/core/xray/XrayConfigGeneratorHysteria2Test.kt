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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import space.getsub.core.model.Applied
import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.Hysteria2Congestion
import space.getsub.core.model.Hysteria2Obfuscation
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Tls
import space.getsub.core.model.Hysteria2Unsupported
import space.getsub.core.model.NoiseItem
import space.getsub.core.model.NoisePayload
import space.getsub.core.model.NoiseSpec
import space.getsub.core.model.Obfuscation
import space.getsub.core.model.PortHopping
import space.getsub.core.model.Profile
import space.getsub.core.model.XrayRange

/**
 * M9-D1 spec §4: Hysteria2 in Xray v26.10.10's layout.
 *
 * The goldens under `resources/hysteria2/` are hand-written from Xray-core v26.10.10's
 * `infra/conf/hysteria.go` (`HysteriaClientConfig`), `transport_method.go:762` (`HysteriaConfig`),
 * `transport_finalmask.go` (`Salamander` :728, `UDPHop` :1053, `QuicParamsConfig` :1132) and
 * `transport_security.go:314` (`pinnedPeerCertSha256`), not captured from this generator.
 */
class XrayConfigGeneratorHysteria2Test {
    private val settings = TunnelSettings(socksPort = 10808, dnsServer = "1.1.1.1", enableSniffing = true)
    private val pinA = "ab".repeat(32)
    private val pinB = "cd".repeat(32)
    private val hopping = PortHopping(ports = listOf(20000..30000, 40000..40000))
    private val salamander = Hysteria2Obfuscation.Salamander("obfs-password")
    private val brutal = Hysteria2Congestion.Brutal("100 mbps", "50 mbps")

    private val plain =
        Hysteria2Outbound(
            address = "hy.example.com",
            port = 443,
            password = "hy2-password",
            tls = Hysteria2Tls(serverName = "sni.example"),
        )

    private val noise =
        Applied(
            NoiseSpec(listOf(NoiseItem(NoisePayload.Random(XrayRange(10, 20), XrayRange(0, 255)), XrayRange(10, 16)))),
            replaceOwn = false,
        )

    private val fragment =
        Applied(
            FragmentSpec(FragmentPackets.TlsHello, XrayRange(50, 100), XrayRange(10, 20), XrayRange(10, 10)),
            replaceOwn = false,
        )

    private fun generate(
        outbound: Hysteria2Outbound,
        tunnel: TunnelSettings = settings,
    ): ConfigResult = XrayConfigGenerator.generate(Profile(id = "id", name = "n", outbound = outbound), tunnel)

    private fun json(
        outbound: Hysteria2Outbound,
        tunnel: TunnelSettings = settings,
    ): String {
        val result = generate(outbound, tunnel)
        // Never the outbound in the message: it carries secrets (ARCHITECTURE.md §5.6).
        check(result is ConfigResult.Ok) { "expected ConfigResult.Ok" }
        return result.json
    }

    private fun golden(name: String) = checkNotNull(javaClass.getResource("/hysteria2/$name")).readText().trimEnd()

    private fun proxy(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject.getValue("outbounds").jsonArray[0].jsonObject

    private fun JsonObject.stream(): JsonObject = getValue("streamSettings").jsonObject

    private fun udpMaskTypes(json: String): List<String> =
        proxy(json).stream()["finalmask"]?.jsonObject?.get("udp")?.jsonArray
            ?.map { it.jsonObject.getValue("type").jsonPrimitive.content }
            .orEmpty()

    // ── Goldens ─────────────────────────────────────────────────────────────

    @Test fun `plain matches its golden`() {
        json(plain) shouldBe golden("plain.json")
    }

    @Test fun `pins are emitted comma-joined in pinnedPeerCertSha256`() {
        json(plain.copy(tls = plain.tls.copy(pins = listOf(pinA, pinB)))) shouldBe golden("pins.json")
    }

    @Test fun `salamander matches its golden`() {
        json(plain.copy(obfuscation = salamander)) shouldBe golden("salamander.json")
    }

    @Test fun `gecko is salamander with a packetSize range`() {
        json(plain.copy(obfuscation = Hysteria2Obfuscation.Gecko("obfs-password", 100, 1200))) shouldBe
            golden("gecko.json")
    }

    @Test fun `hopping with the default interval matches its golden`() {
        json(plain.copy(hopping = hopping)) shouldBe golden("hopping.json")
    }

    @Test fun `hopping with a custom interval matches its golden`() {
        json(plain.copy(hopping = hopping.copy(intervalSeconds = 10))) shouldBe golden("hopping-interval.json")
    }

    @Test fun `brutal matches its golden`() {
        json(plain.copy(congestion = brutal)) shouldBe golden("brutal.json")
    }

    @Test fun `bbr with a profile matches its golden`() {
        json(plain.copy(congestion = Hysteria2Congestion.Bbr("aggressive"))) shouldBe golden("bbr-profile.json")
    }

    @Test fun `everything combined matches its golden`() {
        val all =
            plain.copy(
                tls = plain.tls.copy(pins = listOf(pinA, pinB)),
                hopping = hopping.copy(intervalSeconds = 10),
                obfuscation = salamander,
                congestion = brutal,
            )
        json(all) shouldBe golden("combined.json")
    }

    @Test fun `user noise alone is the only udp mask`() {
        json(plain, settings.copy(obfuscation = Obfuscation(null, noise))) shouldBe golden("noise.json")
    }

    @Test fun `user noise sits between salamander and udphop`() {
        val tunnel = settings.copy(obfuscation = Obfuscation(fragment, noise))
        json(plain.copy(obfuscation = salamander, hopping = hopping), tunnel) shouldBe
            golden("salamander-noise-hopping.json")
    }

    @Test fun `an address pin dials the ip and fills a blank serverName with the domain`() {
        val pinned = settings.copy(addressPin = ServerAddressPin(domain = "hy.example.com", ip = "203.0.113.5"))
        json(plain.copy(tls = plain.tls.copy(serverName = "")), pinned) shouldBe golden("address-pin.json")
    }

    // ── Rules ───────────────────────────────────────────────────────────────

    @Test fun `udp mask order is salamander or gecko, then noise, then udphop, for every subset`() {
        val tunnelNoise = settings.copy(obfuscation = Obfuscation(null, noise))
        val obfs = listOf(Hysteria2Obfuscation.None, salamander, Hysteria2Obfuscation.Gecko("obfs-password", 1, 2048))
        for (o in obfs) {
            for (n in listOf(settings, tunnelNoise)) {
                for (h in listOf(null, hopping)) {
                    val expected =
                        listOfNotNull(
                            "salamander".takeIf { o != Hysteria2Obfuscation.None },
                            "noise".takeIf { n === tunnelNoise },
                            "udphop".takeIf { h != null },
                        )
                    withClue("$o, noise=${n === tunnelNoise}, hop=${h != null}") {
                        udpMaskTypes(json(plain.copy(obfuscation = o, hopping = h), n)) shouldBe expected
                    }
                }
            }
        }
    }

    @Test fun `fragment never applies to hysteria2`() {
        val stream = proxy(json(plain, settings.copy(obfuscation = Obfuscation(fragment, null)))).stream()
        stream.containsKey("finalmask") shouldBe false
        json(plain, settings.copy(obfuscation = Obfuscation(fragment, noise))) shouldNotContain "\"tcp\""
    }

    @Test fun `quicParams appears only when congestion is set`() {
        json(plain) shouldNotContain "quicParams"
        json(plain.copy(obfuscation = salamander, hopping = hopping)) shouldNotContain "quicParams"
        val bbr = proxy(json(plain.copy(congestion = Hysteria2Congestion.Bbr()))).stream()
        bbr.getValue("finalmask").jsonObject.getValue("quicParams").jsonObject.keys shouldBe setOf("congestion")
    }

    @Test fun `brutal omits a blank direction`() {
        val quic =
            proxy(json(plain.copy(congestion = Hysteria2Congestion.Brutal("100 mbps", ""))))
                .stream()
                .getValue("finalmask")
                .jsonObject
                .getValue("quicParams")
                .jsonObject
        quic.keys shouldBe setOf("congestion", "brutalUp")
    }

    @Test fun `an address pin keeps a serverName that is set`() {
        val pinned = settings.copy(addressPin = ServerAddressPin(domain = "hy.example.com", ip = "203.0.113.5"))
        val tls = proxy(json(plain, pinned)).stream().getValue("tlsSettings").jsonObject
        tls.getValue("serverName").jsonPrimitive.content shouldBe "sni.example"
    }

    @Test fun `an empty alpn list is omitted`() {
        proxy(json(plain.copy(tls = plain.tls.copy(alpn = emptyList())))).stream()
            .getValue("tlsSettings").jsonObject.keys shouldBe setOf("serverName")
    }

    @Test fun neverEmitsAllowInsecureOrUdpHop() {
        val everything =
            plain.copy(
                tls = plain.tls.copy(pins = listOf(pinA), skipChecksRequested = true),
                hopping = hopping,
                obfuscation = Hysteria2Obfuscation.Gecko("obfs-password", 100, 1200),
                congestion = brutal,
            )
        val tunnels =
            listOf(
                settings,
                settings.copy(obfuscation = Obfuscation(fragment, noise)),
                settings.copy(addressPin = ServerAddressPin(domain = "hy.example.com", ip = "203.0.113.5")),
            )
        for (outbound in listOf(plain, plain.copy(tls = plain.tls.copy(skipChecksRequested = true)), everything)) {
            for (tunnel in tunnels) {
                val text = json(outbound, tunnel)
                text shouldNotContain "allowInsecure"
                text shouldNotContain "udpHop"
                text shouldNotContain "fingerprint"
                val hysteria = proxy(text).stream().getValue("hysteriaSettings").jsonObject
                hysteria.keys shouldBe setOf("version", "auth")
                for (legacy in listOf("congestion", "up", "down", "udphop")) {
                    hysteria.containsKey(legacy) shouldBe false
                }
            }
        }
    }

    @Test fun notConnectableRowIsUnsupported() {
        val mask = generate(plain.copy(notConnectableReason = Hysteria2Unsupported.UnknownMask))
        mask.shouldBeInstanceOf<ConfigResult.Unsupported>()
        mask.protocol shouldBe "Hysteria2 mask"
        val cc = generate(plain.copy(notConnectableReason = Hysteria2Unsupported.UnknownCongestion))
        cc.shouldBeInstanceOf<ConfigResult.Unsupported>()
        cc.protocol shouldBe "Hysteria2 congestion"
        mask.toString() shouldNotContain "hy2-password"
    }

    @Test fun `escapes every string field`() {
        val malicious =
            Hysteria2Outbound(
                address = injection("address"),
                port = 443,
                password = injection("password"),
                tls =
                Hysteria2Tls(
                    serverName = injection("sni"),
                    alpn = listOf(injection("alpn")),
                    pins = listOf(injection("pin")),
                ),
                obfuscation = Hysteria2Obfuscation.Salamander(injection("obfs")),
                congestion = Hysteria2Congestion.Brutal(injection("up"), injection("down")),
            )
        val text = json(malicious)
        text shouldContain "x\\\",\\\"listen\\\":\\\"0.0.0.0"
        text shouldNotContain "\"listen\":\"0.0.0.0\""
        // Every value survives a parse as exactly what the model held.
        val out = proxy(text)
        out.getValue("settings").jsonObject.getValue("address").jsonPrimitive.content shouldBe injection("address")
        out.stream().getValue("hysteriaSettings").jsonObject.getValue("auth").jsonPrimitive.content shouldBe
            injection("password")
        val tls = out.stream().getValue("tlsSettings").jsonObject
        tls.getValue("serverName").jsonPrimitive.content shouldBe injection("sni")
        tls.getValue("pinnedPeerCertSha256").jsonPrimitive.content shouldBe injection("pin")
        Json.parseToJsonElement(text).jsonObject.getValue("inbounds").jsonArray.size shouldBe 1
    }

    @Test fun `generation is deterministic`() {
        val all = plain.copy(hopping = hopping, obfuscation = salamander, congestion = brutal)
        json(all) shouldBe json(all)
    }

    private fun injection(field: String): String = "$field-\"\\\nx\",\"listen\":\"0.0.0.0"
}
