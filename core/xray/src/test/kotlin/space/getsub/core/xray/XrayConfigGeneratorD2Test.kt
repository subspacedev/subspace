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
import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.KcpHeader
import space.getsub.core.model.KcpObfuscation
import space.getsub.core.model.KcpUnsupported
import space.getsub.core.model.NoiseItem
import space.getsub.core.model.NoisePayload
import space.getsub.core.model.NoiseSpec
import space.getsub.core.model.Obfuscation
import space.getsub.core.model.Outbound
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.TrojanOutbound
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.VmessOutbound
import space.getsub.core.model.WireGuardOutbound
import space.getsub.core.model.WireGuardUnsupported
import space.getsub.core.model.XrayRange

/**
 * M9-D2 spec §3: WireGuard, the HTTP proxy, mKCP and HTTPUpgrade in Xray v26.10.10's layout.
 *
 * The goldens under `resources/d2/` were written by hand from Xray-core v26.10.10's `infra/conf/`
 * (`wireguard.go` `WireGuardConfig`, `http.go` `HTTPClientConfig` flat form, `transport_method.go`
 * `KCPConfig`/`HttpUpgradeConfig`, `transport_finalmask.go` `MkcpLegacy`) and research §1-§4, not captured
 * from this generator, and every one passes `xray run -test` on the v26.10.10 binary (task 6 report).
 */
class XrayConfigGeneratorD2Test {
    private val settings =
        TunnelSettings(
            socksPort = 10808,
            dnsServer = "1.1.1.1",
            enableSniffing = true,
            proxyCredentials = TEST_CREDENTIALS,
        )
    private val uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"
    private val tlsK = Security.Tls("k.example.com", "chrome", allowInsecure = false)
    private val tlsU = Security.Tls("u.example.com", "chrome", allowInsecure = false)
    private val reality =
        Security.Reality("www.microsoft.com", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", "01", "chrome", "/")

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
    private val both = settings.copy(obfuscation = Obfuscation(fragment, noise))

    // ── Fixtures ────────────────────────────────────────────────────────────

    private val wireGuard =
        WireGuardOutbound(
            address = "wg.example.com",
            port = 51820,
            secretKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
            localAddresses = listOf("10.9.0.2/32", "fd00::2/128"),
            peerPublicKey = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=",
            preSharedKey = null,
        )
    private val wireGuardFull =
        wireGuard.copy(
            preSharedKey = "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f",
            mtu = 1380,
            reserved = listOf(1, 2, 3),
            keepAlive = 25,
            allowedIps = listOf("0.0.0.0/0"),
        )

    private val http = HttpOutbound("proxy.example.com", 8080, null, null, Security.None)
    private val httpAuth = http.copy(username = "proxy-user", password = "proxy-pass")
    private val httpTls = httpAuth.copy(security = Security.Tls("proxy.example.com", "chrome", false))

    private fun kcpObfs(
        seed: String? = null,
        header: KcpHeader? = null,
        original: Boolean = false,
    ) = KcpObfuscation(seed, header, original)

    private fun vmessKcp(
        obfuscation: KcpObfuscation,
        network: String = "kcp",
    ) = VmessOutbound(
        "k.example.com",
        4000,
        uuid,
        0,
        "auto",
        StreamSettings(network, Security.None, TransportOptions.Kcp(null, null, obfuscation)),
    )

    private val vlessKcpTls =
        VlessOutbound(
            "k.example.com",
            4000,
            uuid,
            null,
            StreamSettings("kcp", tlsK, TransportOptions.Kcp(1350, 20, kcpObfs(header = KcpHeader.UTP))),
        )

    private val vlessUpgrade =
        VlessOutbound(
            "u.example.com",
            443,
            uuid,
            null,
            StreamSettings("httpupgrade", tlsU, TransportOptions.HttpUpgrade("cdn.example.com", "/up?ed=2048")),
        )

    private fun generate(
        outbound: Outbound,
        tunnel: TunnelSettings = settings,
    ): ConfigResult = XrayConfigGenerator.generate(Profile(id = "id", name = "n", outbound = outbound), tunnel)

    private fun json(
        outbound: Outbound,
        tunnel: TunnelSettings = settings,
    ): String {
        val result = generate(outbound, tunnel)
        // Never the outbound in the message: it carries secrets (ARCHITECTURE.md §5.6).
        check(result is ConfigResult.Ok) { "expected ConfigResult.Ok, got $result" }
        return result.json
    }

    private fun golden(name: String) = checkNotNull(javaClass.getResource("/d2/$name")).readText().trimEnd()

    private fun proxy(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject.getValue("outbounds").jsonArray[0].jsonObject

    private fun JsonObject.stream(): JsonObject = getValue("streamSettings").jsonObject

    private fun pinned(
        domain: String,
        ip: String,
    ) = settings.copy(addressPin = ServerAddressPin(domain = domain, ip = ip))

    // ── WireGuard goldens ───────────────────────────────────────────────────

    @Test fun `wireguard plain matches its golden`() {
        json(wireGuard) shouldBe golden("wireguard-plain.json")
    }

    @Test fun `wireguard with psk, reserved, keepAlive, mtu and allowedIPs matches its golden`() {
        json(wireGuardFull) shouldBe golden("wireguard-full.json")
    }

    @Test fun `wireguard carries user noise in streamSettings finalmask and never the fragment`() {
        json(wireGuard, both) shouldBe golden("wireguard-noise.json")
    }

    @Test fun `wireguard with an address pin dials the pinned ip in endpoint`() {
        json(wireGuard, pinned("wg.example.com", "203.0.113.7")) shouldBe golden("wireguard-address-pin.json")
    }

    @Test fun `an ipv6 endpoint is bracketed`() {
        json(wireGuard.copy(address = "2001:db8::1")) shouldBe golden("wireguard-ipv6-endpoint.json")
        val pinnedV6 = proxy(json(wireGuard, pinned("wg.example.com", "2001:db8::7")))
        pinnedV6.getValue("settings").jsonObject.getValue("peers").jsonArray[0].jsonObject
            .getValue("endpoint").jsonPrimitive.content shouldBe "[2001:db8::7]:51820"
    }

    @Test fun `wireguard omits an empty address and allowedIPs list rather than emitting an empty array`() {
        // research §1.1: an explicit allowedIPs [] routes nothing; an absent one takes the defaults.
        val out = proxy(json(wireGuard.copy(localAddresses = emptyList(), allowedIps = emptyList())))
        val s = out.getValue("settings").jsonObject
        s.containsKey("address") shouldBe false
        s.getValue("peers").jsonArray[0].jsonObject.containsKey("allowedIPs") shouldBe false
        out.containsKey("streamSettings") shouldBe false
    }

    @Test fun `a wireguard row with a reason, an invalid key or a bad reserved is refused by a fixed name`() {
        for (reason in WireGuardUnsupported.entries) {
            generate(wireGuard.copy(notConnectableReason = reason)).shouldBeInstanceOf<ConfigResult.Unsupported>()
        }
        val badKeys =
            listOf(
                wireGuard.copy(secretKey = "not-a-key"),
                wireGuard.copy(peerPublicKey = ""),
                wireGuard.copy(preSharedKey = "short"),
            )
        for (bad in badKeys) {
            val result = generate(bad)
            result.shouldBeInstanceOf<ConfigResult.Unsupported>()
            result.protocol shouldBe "WireGuard key"
            result.toString() shouldNotContain "not-a-key"
        }
        generate(wireGuard.copy(reserved = listOf(1, 2))).shouldBeInstanceOf<ConfigResult.Unsupported>()
    }

    // Ruling D2-R15: an editor or stale row with a bare allowed IP would pass `xray -test` and fail on connect
    // (wireguard-go's netip.ParsePrefix, device/uapi.go:382), so it is refused by name before the core sees it.
    @Test fun `a wireguard allowed ip without a valid prefix is refused by a fixed name`() {
        for (bad in listOf("10.1.2.3", "fd00::1", "example.com")) {
            val result = generate(wireGuard.copy(allowedIps = listOf("0.0.0.0/0", bad)))
            result.shouldBeInstanceOf<ConfigResult.Unsupported>()
            result.protocol shouldBe "WireGuard allowed IPs"
        }
        generate(wireGuard.copy(notConnectableReason = WireGuardUnsupported.InvalidAllowedIp))
            .shouldBeInstanceOf<ConfigResult.Unsupported>()
            .protocol shouldBe "WireGuard allowed IPs"
    }

    // ── HTTP goldens ────────────────────────────────────────────────────────

    @Test fun `http without auth or tls matches its golden`() {
        json(http) shouldBe golden("http-plain.json")
    }

    @Test fun `http with auth matches its golden`() {
        json(httpAuth) shouldBe golden("http-auth.json")
    }

    @Test fun `http with tls and auth matches its golden`() {
        json(httpTls) shouldBe golden("http-tls-auth.json")
    }

    @Test fun `http carries the fragment and never noise`() {
        json(httpTls, both) shouldBe golden("http-tls-fragment.json")
    }

    @Test fun `http with an address pin dials the ip and fills a blank serverName`() {
        val blankSni = http.copy(security = Security.Tls("", "chrome", false))
        json(blankSni, pinned("proxy.example.com", "203.0.113.8")) shouldBe golden("http-address-pin.json")
    }

    @Test fun `http drops a password without a username, and never emits allowInsecure`() {
        val s = proxy(json(http.copy(password = "orphan"))).getValue("settings").jsonObject
        s.keys shouldBe setOf("address", "port")
        json(http.copy(security = Security.Tls("proxy.example.com", "chrome", allowInsecure = true))) shouldNotContain
            "allowInsecure"
    }

    @Test fun `http over reality is refused`() {
        generate(http.copy(security = reality)).shouldBeInstanceOf<ConfigResult.Unsupported>()
    }

    // ── mKCP goldens: research §3.3's translation rows ──────────────────────

    @Test fun `each mkcp translation row matches its golden`() {
        val rows =
            listOf(
                kcpObfs() to "kcp-bare.json",
                kcpObfs(original = true) to "kcp-original.json",
                kcpObfs(seed = "kcp-seed") to "kcp-seed.json",
                // D2-R3: header-only is [original, header].
                kcpObfs(header = KcpHeader.SRTP, original = true) to "kcp-header-only.json",
                kcpObfs(seed = "kcp-seed", header = KcpHeader.DTLS) to "kcp-seed-header.json",
                kcpObfs(seed = "kcp-seed", header = KcpHeader.WECHAT) to "kcp-wechat.json",
            )
        for ((obfuscation, name) in rows) {
            withClue(name) { json(vmessKcp(obfuscation)) shouldBe golden(name) }
        }
    }

    @Test fun `header-only without the legacyOriginal flag still gets original first`() {
        json(vmessKcp(kcpObfs(header = KcpHeader.SRTP))) shouldBe golden("kcp-header-only.json")
    }

    @Test fun `mkcp puts user noise last and never the fragment`() {
        json(vmessKcp(kcpObfs(seed = "kcp-seed", header = KcpHeader.WECHAT)), both) shouldBe golden("kcp-noise.json")
    }

    @Test fun `tls over mkcp with mtu and tti matches its golden`() {
        json(vlessKcpTls) shouldBe golden("kcp-tls.json")
    }

    @Test fun `mkcp with an address pin dials the ip and fills a blank serverName`() {
        val blank =
            vlessKcpTls.copy(
                stream =
                vlessKcpTls.stream.copy(
                    security = tlsK.copy(serverName = ""),
                    transport = TransportOptions.Kcp(1350, 20, kcpObfs(seed = "kcp-seed")),
                ),
            )
        json(blank, pinned("k.example.com", "203.0.113.9")) shouldBe golden("kcp-address-pin.json")
    }

    @Test fun `R10 a kcp row with no transport options generates bare defaults, and mkcp is written kcp`() {
        val row =
            VmessOutbound("k.example.com", 4000, uuid, 0, "auto", StreamSettings("mkcp", Security.None))
        json(row) shouldBe golden("kcp-none-transport.json")
        json(vmessKcp(kcpObfs(), network = "mKCP")) shouldBe golden("kcp-bare.json")
    }

    @Test fun neverEmitsKcpHeaderOrSeed() {
        val obfuscations =
            listOf(kcpObfs(), kcpObfs(original = true), kcpObfs(seed = "kcp-seed")) +
                KcpHeader.entries.flatMap { h ->
                    listOf(kcpObfs(header = h), kcpObfs(header = h, original = true), kcpObfs("kcp-seed", h))
                }
        val tunnels = listOf(settings, both, pinned("k.example.com", "203.0.113.9"))
        val cases =
            obfuscations.flatMap { o ->
                tunnels.flatMap { tunnel ->
                    listOf(Security.None, tlsK).map { security -> Triple(o, tunnel, security) }
                }
            }
        for ((o, tunnel, security) in cases) {
            val row = vmessKcp(o).let { it.copy(stream = it.stream.copy(security = security)) }
            val stream = proxy(json(row, tunnel)).stream()
            withClue("$o $security") {
                val kcp = stream.getValue("kcpSettings").jsonObject
                kcp.containsKey("header") shouldBe false
                kcp.containsKey("seed") shouldBe false
                (kcp.keys - setOf("mtu", "tti")) shouldBe emptySet()
                stream.getValue("network").jsonPrimitive.content shouldBe "kcp"
                (stream["finalmask"]?.jsonObject?.containsKey("tcp") ?: false) shouldBe false
            }
        }
    }

    @Test fun `a kcp row the model cannot write, or with settings the core rejects, is refused`() {
        for (reason in KcpUnsupported.entries) {
            val result = generate(vmessKcp(KcpObfuscation(null, null, false, reason)))
            result.shouldBeInstanceOf<ConfigResult.Unsupported>()
        }
        for ((mtu, tti) in listOf(20 to null, 65536 to null, null to 9, null to 1001)) {
            val row =
                vmessKcp(kcpObfs()).let {
                    it.copy(stream = it.stream.copy(transport = TransportOptions.Kcp(mtu, tti, kcpObfs())))
                }
            withClue("$mtu $tti") { generate(row).shouldBeInstanceOf<ConfigResult.Unsupported>() }
        }
    }

    @Test fun realityOverKcpIsUnsupported() {
        for (network in listOf("kcp", "mkcp")) {
            val row = vmessKcp(kcpObfs(), network).let { it.copy(stream = it.stream.copy(security = reality)) }
            val result = generate(row)
            result.shouldBeInstanceOf<ConfigResult.Unsupported>()
            result.protocol shouldBe "REALITY over mKCP"
        }
        val noOptions = VlessOutbound("k.example.com", 4000, uuid, null, StreamSettings("kcp", reality))
        generate(noOptions).shouldBeInstanceOf<ConfigResult.Unsupported>()
    }

    // ── HTTPUpgrade goldens ─────────────────────────────────────────────────

    @Test fun `httpupgrade with tls matches its golden`() {
        json(vlessUpgrade) shouldBe golden("httpupgrade-tls.json")
    }

    @Test fun `httpupgrade carries the fragment and never noise`() {
        json(vlessUpgrade, both) shouldBe golden("httpupgrade-fragment.json")
    }

    @Test fun `httpupgrade without tls fills a blank host from an address pin`() {
        val row =
            VmessOutbound(
                "u.example.com",
                80,
                uuid,
                0,
                "auto",
                StreamSettings("httpupgrade", Security.None, TransportOptions.HttpUpgrade("", "/")),
            )
        json(row, pinned("u.example.com", "203.0.113.10")) shouldBe golden("httpupgrade-address-pin.json")
    }

    @Test fun `R10 an httpupgrade row with no transport options generates host empty and path slash`() {
        val row = TrojanOutbound("u.example.com", 443, "trojan-password", StreamSettings("httpupgrade", tlsU))
        json(row) shouldBe golden("httpupgrade-none-transport.json")
    }

    @Test fun neverPutsHostInHttpUpgradeHeaders() {
        val rows =
            listOf(
                vlessUpgrade,
                vlessUpgrade.copy(stream = vlessUpgrade.stream.copy(security = Security.None)),
                vlessUpgrade.copy(stream = StreamSettings("httpupgrade", Security.None)),
                vlessUpgrade.copy(
                    stream = vlessUpgrade.stream.copy(transport = TransportOptions.HttpUpgrade("", "/")),
                ),
            )
        val tunnels = listOf(settings, both, pinned("u.example.com", "203.0.113.10"))
        for (row in rows) {
            for (tunnel in tunnels) {
                val upgrade = proxy(json(row, tunnel)).stream().getValue("httpupgradeSettings").jsonObject
                withClue("$row") {
                    upgrade.keys shouldBe setOf("host", "path")
                    upgrade.containsKey("headers") shouldBe false
                }
            }
        }
    }

    @Test fun realityOverHttpUpgradeIsUnsupported() {
        val row = vlessUpgrade.copy(stream = vlessUpgrade.stream.copy(security = reality))
        val result = generate(row)
        result.shouldBeInstanceOf<ConfigResult.Unsupported>()
        result.protocol shouldBe "REALITY over HTTPUpgrade"
        val noOptions = TrojanOutbound("u.example.com", 443, "p", StreamSettings("HTTPUpgrade", reality))
        generate(noOptions).shouldBeInstanceOf<ConfigResult.Unsupported>()
    }

    @Test fun `kcp or httpupgrade options under another network are refused, never emitted`() {
        val kcpUnderWs = vmessKcp(kcpObfs(seed = "kcp-seed")).let { it.copy(stream = it.stream.copy(network = "ws")) }
        generate(kcpUnderWs).shouldBeInstanceOf<ConfigResult.Unsupported>()
        val upgradeUnderKcp = vlessUpgrade.copy(stream = vlessUpgrade.stream.copy(network = "kcp"))
        generate(upgradeUnderKcp).shouldBeInstanceOf<ConfigResult.Unsupported>()
    }

    // ── Injection and escaping ──────────────────────────────────────────────

    @Test fun `escapes every string field`() {
        val evilWg =
            wireGuard.copy(
                address = injection("host"),
                localAddresses = listOf(injection("addr")),
            )
        // Keys and allowed IPs are validated before writing, so an injected one never reaches the text at all.
        generate(wireGuard.copy(secretKey = injection("sk"))).shouldBeInstanceOf<ConfigResult.Unsupported>()
        val evilAllowed = wireGuard.copy(allowedIps = listOf(injection("allowed")))
        generate(evilAllowed).shouldBeInstanceOf<ConfigResult.Unsupported>()
        val texts =
            listOf(
                json(evilWg),
                json(HttpOutbound(injection("addr"), 8080, injection("user"), injection("pass"), Security.None)),
                json(vmessKcp(kcpObfs(seed = injection("seed"), header = KcpHeader.WECHAT))),
                json(
                    vlessUpgrade.copy(
                        stream =
                        vlessUpgrade.stream.copy(
                            transport = TransportOptions.HttpUpgrade(injection("host"), injection("path")),
                        ),
                    ),
                ),
            )
        for (text in texts) {
            text shouldContain "x\\\",\\\"listen\\\":\\\"0.0.0.0"
            text shouldNotContain "\"listen\":\"0.0.0.0\""
            Json.parseToJsonElement(text).jsonObject.getValue("inbounds").jsonArray.size shouldBe 1
        }
        val http = proxy(texts[1]).getValue("settings").jsonObject
        http.getValue("pass").jsonPrimitive.content shouldBe injection("pass")
        val seed =
            proxy(texts[2]).stream().getValue("finalmask").jsonObject.getValue("udp").jsonArray[0].jsonObject
                .getValue("settings").jsonObject.getValue("value").jsonPrimitive.content
        seed shouldBe injection("seed")
        val peer = proxy(texts[0]).getValue("settings").jsonObject.getValue("peers").jsonArray[0].jsonObject
        peer.getValue("endpoint").jsonPrimitive.content shouldBe "${injection("host")}:51820"
    }

    @Test fun `generation is deterministic`() {
        json(wireGuardFull, both) shouldBe json(wireGuardFull, both)
        val kcp = vmessKcp(kcpObfs("s", KcpHeader.WECHAT))
        json(kcp, both) shouldBe json(kcp, both)
    }

    private fun injection(field: String): String = "$field-\"\\\nx\",\"listen\":\"0.0.0.0"
}
