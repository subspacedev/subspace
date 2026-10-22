// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.getsub.core.model.Applied
import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.KcpHeader
import space.getsub.core.model.KcpObfuscation
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
import space.getsub.core.model.XrayRange
import java.io.File

/**
 * M9-D2 spec §3/§7: WireGuard, the HTTP proxy, mKCP and HTTPUpgrade configs Subspace writes, and raw
 * kcp / WireGuard configs it only rewrites or projects, against the real libXray v26.10.10 core
 * (`testXray` through [XrayController.validate], which runs `core.New`).
 *
 * ARCHITECTURE.md §10.5: `XrayConfigGeneratorD2Test` proves what we emit against hand-written goldens;
 * only the core proves it is accepted. Every case goes through [XrayConfigGenerator].
 *
 * Failure messages carry a case label of our own making, never the core's message, which can quote the
 * config back (ARCHITECTURE.md §5.6). Each test collects every failing label before asserting. Hosts
 * without TLS are private literals, which the core's plaintext rule allows.
 *
 * What this cannot catch (ruling D2-R15): a WireGuard `allowedIPs` entry without a prefix. `core.New` stores
 * the strings, and wireguard-go reads each one with `netip.ParsePrefix` (`device/uapi.go:382`) only when the
 * outbound first dials (`proxy/wireguard/client.go:322`), so a bare address passes here and fails on connect.
 * That rule is pinned by unit tests instead: `WireGuardRules.normaliseAllowedIp`, the parsers and
 * `TypedRefusal`.
 *
 * Camel-case names, no backticks: DEX 040 restricts synthetic class names in instrumented tests.
 */
@RunWith(AndroidJUnit4::class)
class D2XrayTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings =
        TunnelSettings(
            socksPort = 41080,
            dnsServer = "1.1.1.1",
            enableSniffing = true,
            proxyCredentials = TEST_CREDENTIALS,
        )
    private val uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"
    private val privateHost = "192.168.1.10"
    private val tls = Security.Tls("k.example.com", "chrome", allowInsecure = false)

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
    private val withNoise = settings.copy(obfuscation = Obfuscation(null, noise))
    private val withFragment = settings.copy(obfuscation = Obfuscation(fragment, null))
    private val withBoth = settings.copy(obfuscation = Obfuscation(fragment, noise))

    private fun pinned(
        domain: String,
        ip: String,
    ) = settings.copy(addressPin = ServerAddressPin(domain = domain, ip = ip))

    private suspend fun accepted(json: String): Boolean = runCatching { XrayController().validate(json) }.isSuccess

    private class Case(
        val label: String,
        val outbound: Outbound,
        val tunnel: TunnelSettings,
        val expectedMasks: List<String> = emptyList(),
    )

    private suspend fun rejectedLabels(cases: List<Case>): List<String> =
        cases.mapNotNull { case ->
            val result =
                XrayConfigGenerator.generate(Profile(id = "id", name = "n", outbound = case.outbound), case.tunnel)
            when {
                result !is ConfigResult.Ok -> "${case.label} (generator refused)"
                udpMaskTypes(result.json) != case.expectedMasks -> "${case.label} (udp masks)"
                !accepted(result.json) -> "${case.label} (core refused)"
                else -> null
            }
        }

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

    private val http = HttpOutbound(privateHost, 8080, null, null, Security.None)
    private val httpAuth = http.copy(username = "proxy-user", password = "proxy-pass")
    private val proxyTls = Security.Tls("proxy.example.com", "chrome", false)
    private val httpTls = httpAuth.copy(address = "proxy.example.com", security = proxyTls)

    private fun kcpStream(
        o: KcpObfuscation,
        security: Security = Security.None,
        mtu: Int? = null,
        tti: Int? = null,
    ) = StreamSettings("kcp", security, TransportOptions.Kcp(mtu, tti, o))

    private fun upgradeStream(security: Security) =
        StreamSettings("httpupgrade", security, TransportOptions.HttpUpgrade("cdn.example.com", "/up?ed=2048"))

    private fun vless(
        stream: StreamSettings,
        host: String = privateHost,
    ) = VlessOutbound(host, 4000, uuid, null, stream)

    private fun vmess(
        stream: StreamSettings,
        host: String = privateHost,
    ) = VmessOutbound(host, 4000, uuid, 0, "auto", stream)

    private fun trojan(
        stream: StreamSettings,
        host: String = "k.example.com",
    ) = TrojanOutbound(host, 4000, "trojan-password", stream)

    private val mkcp = "mkcp-legacy"
    private companion object {
        const val HEX_KEY_BYTES = 32
    }

    // ── WireGuard ───────────────────────────────────────────────────────────

    @Test
    fun everyWireGuardShapeIsAcceptedByTheRealCore() =
        runTest {
            val cases =
                listOf(
                    Case("plain", wireGuard, settings),
                    Case("full", wireGuardFull, settings),
                    Case("noise", wireGuard, withNoise, listOf("noise")),
                    // Fragment is TCP-only: it must not reach a WireGuard outbound.
                    Case("fragment ignored", wireGuard, withFragment),
                    Case("noise and fragment", wireGuardFull, withBoth, listOf("noise")),
                    Case("ipv6 endpoint", wireGuard.copy(address = "2001:db8::1"), settings),
                    Case("address pin", wireGuard, pinned("wg.example.com", "203.0.113.7")),
                    Case("ipv6 address pin", wireGuard, pinned("wg.example.com", "2001:db8::7")),
                    Case("no local addresses", wireGuard.copy(localAddresses = emptyList()), settings),
                    Case("hex keys", hexKeys, settings),
                )
            assertEquals("rejected WireGuard shapes", emptyList<String>(), rejectedLabels(cases))
        }

    private val hexKeys =
        wireGuard.copy(
            secretKey = "00".repeat(HEX_KEY_BYTES),
            peerPublicKey = "11".repeat(HEX_KEY_BYTES),
        )

    // ── HTTP ────────────────────────────────────────────────────────────────

    @Test
    fun everyHttpShapeIsAcceptedByTheRealCore() =
        runTest {
            val cases =
                listOf(
                    Case("plain", http, settings),
                    Case("auth", httpAuth.copy(address = privateHost, security = Security.None), settings),
                    Case("tls", http.copy(address = "proxy.example.com", security = httpTls.security), settings),
                    Case("tls and auth", httpTls, settings),
                    Case("fragment", httpTls, withFragment),
                    Case("noise ignored", httpTls, withNoise),
                    Case("tls address pin", httpTls, pinned("proxy.example.com", "203.0.113.8")),
                    Case(
                        "tls address pin, blank sni",
                        httpTls.copy(security = Security.Tls("", "chrome", false)),
                        pinned("proxy.example.com", "203.0.113.8"),
                    ),
                )
            assertEquals("rejected HTTP shapes", emptyList<String>(), rejectedLabels(cases))
        }

    // ── mKCP ────────────────────────────────────────────────────────────────

    private val kcpRows: List<Pair<String, KcpObfuscation>> =
        listOf(
            "bare" to KcpObfuscation(null, null, false),
            "original" to KcpObfuscation(null, null, true),
            "seed" to KcpObfuscation("kcp-seed", null, false),
            "header-only" to KcpObfuscation(null, KcpHeader.SRTP, true),
            "seed+header" to KcpObfuscation("kcp-seed", KcpHeader.DTLS, false),
            "wechat" to KcpObfuscation("kcp-seed", KcpHeader.WECHAT, false),
        ) +
            KcpHeader.entries.map { "header ${it.wireName}" to KcpObfuscation("kcp-seed", it, false) }

    /** The mkcp masks the generator must write for [o]: [seed or original, header], noise appended last. */
    private fun kcpMasks(
        o: KcpObfuscation,
        noise: Boolean,
    ): List<String> {
        val own =
            when {
                o.seed != null -> listOf(mkcp)
                o.header != null || o.legacyOriginal -> listOf(mkcp)
                else -> emptyList()
            } + listOfNotNull(mkcp.takeIf { o.header != null })
        return own + listOfNotNull("noise".takeIf { noise })
    }

    @Test
    fun everyMkcpObfuscationRowOnEveryProtocolIsAcceptedByTheRealCore() =
        runTest {
            val cases = mutableListOf<Case>()
            for ((name, o) in kcpRows) {
                for ((tn, tunnel) in listOf("clean" to settings, "noise" to withNoise, "both" to withBoth)) {
                    val masks = kcpMasks(o, tunnel.obfuscation?.noise != null)
                    val stream = kcpStream(o)
                    cases += Case("vless $name $tn", vless(stream), tunnel, masks)
                    cases += Case("vmess $name $tn", vmess(stream), tunnel, masks)
                    // Trojan needs TLS (the core refuses plaintext to a public name).
                    cases += Case("trojan $name $tn", trojan(kcpStream(o, tls)), tunnel, masks)
                }
            }
            assertEquals("rejected mKCP shapes", emptyList<String>(), rejectedLabels(cases))
        }

    @Test
    fun mkcpWithTlsMtuTtiAndAnAddressPinIsAcceptedByTheRealCore() =
        runTest {
            val seeded = KcpObfuscation("kcp-seed", KcpHeader.UTP, false)
            val cases =
                listOf(
                    Case("tls", vless(kcpStream(seeded, tls, 1350, 20), "k.example.com"), settings, listOf(mkcp, mkcp)),
                    Case("tls bare", vless(kcpStream(kcpRows.first().second, tls), "k.example.com"), settings),
                    Case(
                        "tls noise",
                        vless(kcpStream(seeded, tls, 1350, 20), "k.example.com"),
                        withNoise,
                        listOf(mkcp, mkcp, "noise"),
                    ),
                    Case(
                        "tls address pin",
                        vless(kcpStream(seeded, tls.copy(serverName = ""), 1350, 20), "k.example.com"),
                        pinned("k.example.com", "203.0.113.9"),
                        listOf(mkcp, mkcp),
                    ),
                    Case(
                        "plain address pin",
                        vless(kcpStream(seeded), "k.example.com"),
                        pinned("k.example.com", "192.168.1.11"),
                        listOf(mkcp, mkcp),
                    ),
                    Case("mkcp spelling", vmess(StreamSettings("mkcp", Security.None)), settings),
                )
            assertEquals("rejected mKCP option shapes", emptyList<String>(), rejectedLabels(cases))
        }

    // ── HTTPUpgrade ─────────────────────────────────────────────────────────

    @Test
    fun everyHttpUpgradeShapeOnEveryProtocolIsAcceptedByTheRealCore() =
        runTest {
            val plain = upgradeStream(Security.None)
            val secure = upgradeStream(tls)
            val cases = mutableListOf<Case>()
            for ((tn, tunnel) in listOf("clean" to settings, "fragment" to withFragment, "noise" to withNoise)) {
                cases += Case("vless tls $tn", vless(secure, "u.example.com"), tunnel)
                cases += Case("vless plain $tn", vless(plain), tunnel)
                cases += Case("vmess tls $tn", vmess(secure, "u.example.com"), tunnel)
                cases += Case("vmess plain $tn", vmess(plain), tunnel)
                cases += Case("trojan tls $tn", trojan(secure, "u.example.com"), tunnel)
            }
            val blankSni = vless(secure.copy(security = tls.copy(serverName = "")), "u.example.com")
            cases += Case("address pin", blankSni, pinned("u.example.com", "203.0.113.10"))
            val blankHost = StreamSettings("httpupgrade", Security.None, TransportOptions.HttpUpgrade("", "/"))
            val privatePin = pinned("u.example.com", "192.168.1.12")
            cases += Case("plain address pin", vless(blankHost, "u.example.com"), privatePin)
            cases += Case("no transport options", trojan(StreamSettings("httpupgrade", tls), "u.example.com"), settings)
            assertEquals("rejected HTTPUpgrade shapes", emptyList<String>(), rejectedLabels(cases))
        }

    // ── Raw rows ────────────────────────────────────────────────────────────

    /** Classic Xray raw mKCP: `header`/`seed` inside `kcpSettings`, which the v26.10.10 core ignores. */
    private val rawLegacyKcp =
        """
        {
          "log": { "loglevel": "warning" },
          "inbounds": [
            { "tag": "socks", "port": 10808, "listen": "127.0.0.1", "protocol": "socks",
              "settings": { "udp": true } }
          ],
          "outbounds": [
            { "tag": "proxy", "protocol": "vless",
              "settings": { "vnext": [ { "address": "192.168.1.10", "port": 4000,
                "users": [ { "id": "$uuid", "encryption": "none" } ] } ] },
              "streamSettings": {
                "network": "kcp",
                "security": "none",
                "kcpSettings": { "mtu": 1350, "tti": 20, "header": { "type": "wechat-video" }, "seed": "kcp-seed" }
              } },
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" }
          ]
        }
        """.trimIndent()

    private val rawWireGuard =
        """
        {
          "log": { "loglevel": "warning" },
          "inbounds": [
            { "tag": "socks", "port": 10808, "listen": "127.0.0.1", "protocol": "socks",
              "settings": { "udp": true } }
          ],
          "outbounds": [
            { "tag": "proxy", "protocol": "wireguard",
              "settings": {
                "secretKey": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "address": ["10.9.0.2/32"],
                "mtu": 1420,
                "reserved": [1, 2, 3],
                "noKernelTun": true,
                "peers": [ { "publicKey": "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=",
                             "endpoint": "wg.example.com:51820", "keepAlive": 25,
                             "allowedIPs": ["0.0.0.0/0", "::/0"] } ]
              } },
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" }
          ]
        }
        """.trimIndent()

    private fun composed(
        raw: String,
        tunnel: TunnelSettings,
    ): String {
        val assetDir = File(context.filesDir, "geo").apply { mkdirs() }
        try {
            val result = RawConfigComposer.compose(raw, tunnel, assetDir.absolutePath, null)
            check(result is ComposeResult.Ok) { "composer refused the fixture" }
            return result.json
        } finally {
            assetDir.deleteRecursively()
        }
    }

    private fun proxyOutbound(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject.getValue("outbounds").jsonArray
            .map { it.jsonObject }.first { (it["tag"] as? JsonPrimitive)?.content == "proxy" }

    private fun udpMaskTypes(json: String): List<String> {
        val finalmask = proxyOutbound(json)["streamSettings"]?.jsonObject?.get("finalmask") as? JsonObject
        return (finalmask?.get("udp") as? JsonArray)
            ?.map { (it.jsonObject.getValue("type") as JsonPrimitive).content }
            .orEmpty()
    }

    @Test
    fun aRawLegacyKcpConfigIsRewrittenIntoMasksAndPasses() =
        runTest {
            val json = composed(rawLegacyKcp, settings)
            // seed, then the header; the inert keys are gone, so the core reads what the server expects.
            assertEquals(listOf(mkcp, mkcp), udpMaskTypes(json))
            val kcp = proxyOutbound(json).getValue("streamSettings").jsonObject.getValue("kcpSettings").jsonObject
            assertTrue("header survived", "header" !in kcp)
            assertTrue("seed survived", "seed" !in kcp)
            assertTrue("core refused the rewritten raw kcp config", accepted(json))
        }

    @Test
    fun aRawLegacyKcpConfigWithTheUsersNoiseKeepsNoiseLast() =
        runTest {
            val json = composed(rawLegacyKcp, withNoise)
            assertEquals(listOf(mkcp, mkcp, "noise"), udpMaskTypes(json))
            assertTrue("core refused the raw kcp config with noise", accepted(json))
        }

    @Test
    fun aRawWireGuardConfigPassesTestXrayAsWritten() =
        runTest {
            assertTrue("core refused the raw WireGuard config", accepted(rawWireGuard))
        }

    @Test
    fun aRawWireGuardConfigPassesAfterComposing() =
        runTest {
            assertTrue("core refused the composed raw WireGuard config", accepted(composed(rawWireGuard, settings)))
            assertTrue(
                "core refused the composed raw WireGuard config with a pin",
                accepted(composed(rawWireGuard, pinned("wg.example.com", "203.0.113.7"))),
            )
        }
}
