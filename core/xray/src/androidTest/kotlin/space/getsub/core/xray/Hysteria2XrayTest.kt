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
import space.getsub.core.model.Hysteria2Congestion
import space.getsub.core.model.Hysteria2Obfuscation
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Tls
import space.getsub.core.model.NoiseItem
import space.getsub.core.model.NoisePayload
import space.getsub.core.model.NoiseSpec
import space.getsub.core.model.Obfuscation
import space.getsub.core.model.PortHopping
import space.getsub.core.model.Profile
import space.getsub.core.model.XrayRange
import java.io.File

/**
 * M9-D1 spec §4/§7: the Hysteria2 configs Subspace writes, and a raw `hysteria` config it only
 * projects, against the real libXray v26.10.10 core (`testXray`, which runs `core.New`, so the
 * hysteria client, the TLS pin parser and the finalmask masks all build).
 *
 * ARCHITECTURE.md §10.5: `XrayConfigGeneratorHysteria2Test` proves what we emit against hand-written
 * goldens; only the core proves it is accepted. The cases below cover the same option combinations as
 * those goldens but go through [XrayConfigGenerator], so the real path is what is tested.
 *
 * Failure messages carry a case label of our own making, never the core's message, which can quote
 * the config back (ARCHITECTURE.md §5.6). Each test collects every failing label before asserting.
 *
 * Camel-case names, no backticks: DEX 040 restricts synthetic class names in instrumented tests.
 */
@RunWith(AndroidJUnit4::class)
class Hysteria2XrayTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = TunnelSettings(socksPort = 41080, dnsServer = "1.1.1.1", enableSniffing = true)
    private val pinA = "ab".repeat(32)
    private val pinB = "cd".repeat(32)
    private val hopping = PortHopping(ports = listOf(20000..30000, 40000..40000))
    private val salamander = Hysteria2Obfuscation.Salamander("obfs-password")
    private val brutal = Hysteria2Congestion.Brutal("100 mbps", "50 mbps")
    private val gecko = Hysteria2Obfuscation.Gecko("obfs-password", 100, 1200)

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

    private val withNoise = settings.copy(obfuscation = Obfuscation(null, noise))

    private fun profile(outbound: Hysteria2Outbound) = Profile(id = "id", name = "n", outbound = outbound)

    private suspend fun accepted(json: String): Boolean = runCatching { XrayController().validate(json) }.isSuccess

    /** One generated config per option combination; the label names the combination only. */
    private class Case(
        val label: String,
        val outbound: Hysteria2Outbound,
        val tunnel: TunnelSettings,
    ) {
        /** The udp masks the generator must write: obfuscation, then noise, then the hop. */
        val expectedMasks: List<String> =
            listOfNotNull(
                "salamander".takeIf { outbound.obfuscation != Hysteria2Obfuscation.None },
                "noise".takeIf { tunnel.obfuscation?.noise != null },
                "udphop".takeIf { outbound.hopping != null },
            )
    }

    private suspend fun rejectedLabels(cases: List<Case>): List<String> =
        cases.mapNotNull { case ->
            val result = XrayConfigGenerator.generate(profile(case.outbound), case.tunnel)
            when {
                result !is ConfigResult.Ok -> "${case.label} (generator refused)"
                udpMaskTypes(result.json) != case.expectedMasks -> "${case.label} (udp masks)"
                !accepted(result.json) -> "${case.label} (core refused)"
                else -> null
            }
        }

    @Test
    fun everyGeneratorGoldenShapeIsAcceptedByTheRealCore() =
        runTest {
            val tls = plain.tls
            val cases =
                listOf(
                    Case("plain", plain, settings),
                    Case("pins", plain.copy(tls = tls.copy(pins = listOf(pinA, pinB))), settings),
                    Case("salamander", plain.copy(obfuscation = salamander), settings),
                    Case("gecko", plain.copy(obfuscation = gecko), settings),
                    Case("hopping", plain.copy(hopping = hopping), settings),
                    Case("hopping interval", plain.copy(hopping = hopping.copy(intervalSeconds = 10)), settings),
                    Case("brutal", plain.copy(congestion = brutal), settings),
                    // The editor accepts Brutal with only one direction set (final review M8).
                    Case("brutal up only", plain.copy(congestion = Hysteria2Congestion.Brutal("100 mbps", "")), settings),
                    Case("brutal down only", plain.copy(congestion = Hysteria2Congestion.Brutal("", "50 mbps")), settings),
                    Case("bbr profile", plain.copy(congestion = Hysteria2Congestion.Bbr("aggressive")), settings),
                    Case("noise", plain, withNoise),
                    Case(
                        "salamander noise hopping",
                        plain.copy(obfuscation = salamander, hopping = hopping),
                        withNoise,
                    ),
                    Case(
                        "address pin",
                        plain.copy(tls = tls.copy(serverName = "")),
                        settings.copy(addressPin = ServerAddressPin(domain = "hy.example.com", ip = "203.0.113.5")),
                    ),
                    Case(
                        "combined",
                        plain.copy(
                            tls = tls.copy(pins = listOf(pinA, pinB)),
                            hopping = hopping.copy(intervalSeconds = 10),
                            obfuscation = salamander,
                            congestion = brutal,
                        ),
                        withNoise,
                    ),
                )
            assertEquals("rejected Hysteria2 shapes", emptyList<String>(), rejectedLabels(cases))
        }

    /** The full subset product: every obfuscation x noise x hopping x congestion the generator can write. */
    @Test
    fun everyObfuscationNoiseHoppingAndCongestionSubsetIsAcceptedByTheRealCore() =
        runTest {
            val cases = mutableListOf<Case>()
            val obfs = mapOf("none" to Hysteria2Obfuscation.None, "salamander" to salamander, "gecko" to gecko)
            val hops = mapOf("nohop" to null, "hop" to hopping)
            val congestions =
                mapOf(
                    "default" to Hysteria2Congestion.Default,
                    "bbr" to Hysteria2Congestion.Bbr(),
                    "brutal" to brutal,
                )
            val tunnels = mapOf("nonoise" to settings, "noise" to withNoise)
            for ((on, o) in obfs) {
                for ((hn, h) in hops) {
                    for ((cn, c) in congestions) {
                        for ((tn, t) in tunnels) {
                            val outbound = plain.copy(obfuscation = o, hopping = h, congestion = c)
                            cases += Case("$on/$hn/$cn/$tn", outbound, t)
                        }
                    }
                }
            }
            assertEquals("rejected Hysteria2 subsets", emptyList<String>(), rejectedLabels(cases))
        }

    private val rawHysteria =
        """
        {
          "log": { "loglevel": "warning" },
          "inbounds": [
            { "tag": "socks", "port": 10808, "listen": "127.0.0.1", "protocol": "socks",
              "settings": { "udp": true } }
          ],
          "outbounds": [
            { "tag": "proxy", "protocol": "hysteria",
              "settings": { "version": 2, "address": "hy.example.com", "port": 443 },
              "streamSettings": {
                "network": "hysteria",
                "hysteriaSettings": { "version": 2, "auth": "hy2-password" },
                "security": "tls",
                "tlsSettings": { "serverName": "sni.example", "alpn": ["h3"] },
                "finalmask": {
                  "udp": [
                    { "type": "salamander", "settings": { "password": "obfs-password" } },
                    { "type": "udphop",
                      "settings": { "mode": "intervalLocal,intervalRemote", "interval": 30,
                                    "remotePorts": "20000-30000" } }
                  ],
                  "quicParams": { "congestion": "brutal", "brutalUp": "100 mbps", "brutalDown": "50 mbps" }
                }
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

    private fun udpMaskTypes(json: String): List<String> {
        val outbounds = Json.parseToJsonElement(json).jsonObject.getValue("outbounds").jsonArray
        val proxy = outbounds.map { it.jsonObject }.first { (it["tag"] as? JsonPrimitive)?.content == "proxy" }
        val finalmask = proxy.getValue("streamSettings").jsonObject["finalmask"] as? JsonObject
        return (finalmask?.get("udp") as? JsonArray)
            ?.map { (it.jsonObject.getValue("type") as JsonPrimitive).content }
            .orEmpty()
    }

    @Test
    fun aRawHysteriaConfigPassesTestXrayAsWritten() =
        runTest {
            assertTrue("core refused the raw hysteria config", accepted(rawHysteria))
        }

    @Test
    fun aRawHysteriaConfigWithTheUsersNoiseInjectedStillPasses() =
        runTest {
            val json = composed(rawHysteria, withNoise)
            // Ruling R6: noise sits between the config's own salamander and its udphop.
            assertEquals(listOf("salamander", "noise", "udphop"), udpMaskTypes(json))
            assertTrue("core refused the raw hysteria config with noise", accepted(json))
        }

    @Test
    fun aRawHysteriaConfigWithoutOwnMasksAcceptsInjectedNoise() =
        runTest {
            val root = Json.parseToJsonElement(rawHysteria).jsonObject
            val outbounds =
                root.getValue("outbounds").jsonArray.map { outbound ->
                    val o = outbound.jsonObject
                    val stream = o["streamSettings"] as? JsonObject ?: return@map o
                    JsonObject(o + ("streamSettings" to JsonObject(stream - "finalmask")))
                }
            val bare = JsonObject(root + ("outbounds" to JsonArray(outbounds))).toString()
            val json = composed(bare, withNoise)
            assertEquals(listOf("noise"), udpMaskTypes(json))
            assertTrue("core refused injected noise on a bare hysteria outbound", accepted(json))
        }

    /**
     * Final review I4: Remnawave's v26.7.11 `quicParams.udpHop` shape, which v26.10.10 no longer reads.
     * The composer rewrites it into a last `udphop` mask (after the user's noise) and drops the old key,
     * and the core must accept the result.
     */
    @Test
    fun aRawHysteriaConfigWithTheLegacyUdpHopIsRewrittenAndPasses() =
        runTest {
            val root = Json.parseToJsonElement(rawHysteria).jsonObject
            val outbounds =
                root.getValue("outbounds").jsonArray.map { outbound ->
                    val o = outbound.jsonObject
                    val stream = o["streamSettings"] as? JsonObject ?: return@map o
                    val fm = stream.getValue("finalmask").jsonObject
                    val udp = fm.getValue("udp").jsonArray.filterNot { "udphop" in it.toString() }
                    val legacyHop = Json.parseToJsonElement("""{"ports":"20000-30000","interval":15}""")
                    val quic = JsonObject(fm.getValue("quicParams").jsonObject + ("udpHop" to legacyHop))
                    val newFm = JsonObject(fm + ("udp" to JsonArray(udp)) + ("quicParams" to quic))
                    JsonObject(o + ("streamSettings" to JsonObject(stream + ("finalmask" to newFm))))
                }
            val legacy = JsonObject(root + ("outbounds" to JsonArray(outbounds))).toString()
            check("udphop" !in legacy && "udpHop" in legacy) { "fixture rewrite did not apply" }
            val json = composed(legacy, withNoise)
            assertEquals(listOf("salamander", "noise", "udphop"), udpMaskTypes(json))
            assertTrue("the legacy key survived the composer", "udpHop" !in json)
            assertTrue("core refused the rewritten legacy udpHop config", accepted(json))
        }
}
