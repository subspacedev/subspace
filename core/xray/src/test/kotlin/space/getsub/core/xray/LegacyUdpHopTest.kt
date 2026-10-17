// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import space.getsub.core.model.Applied
import space.getsub.core.model.NoiseItem
import space.getsub.core.model.NoisePayload
import space.getsub.core.model.NoiseSpec
import space.getsub.core.model.Obfuscation
import space.getsub.core.model.XrayRange

/**
 * M9-D1 final review I4: Remnawave still writes the v26.7.11 `quicParams.udpHop {ports, interval}`,
 * which v26.10.10 no longer reads, so a raw row would silently stop hopping while its projection
 * says it hops. The composer rewrites it into the `udphop` mask the core reads.
 */
class LegacyUdpHopTest {
    private val settings = TunnelSettings(socksPort = 41080, dnsServer = "1.1.1.1", enableSniffing = true)

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonArray

    private fun JsonArray.stream(i: Int) = this[i].jsonObject["streamSettings"]!!.jsonObject

    private fun JsonObject.finalmask() = this["finalmask"]!!.jsonObject

    private fun JsonObject.udpTypes() =
        (finalmask()["udp"] as? JsonArray)?.map { it.jsonObject["type"]!!.jsonPrimitive.content }.orEmpty()

    private fun hysteria(finalmask: String) =
        """[{"tag":"proxy","protocol":"hysteria","settings":{"version":2,"address":"h.example","port":443},
            "streamSettings":{"network":"hysteria","security":"tls","finalmask":$finalmask}}]"""

    @Test fun `the legacy shape becomes a udphop mask and the key goes`() {
        val out =
            LegacyUdpHop.apply(
                parse(hysteria("""{"quicParams":{"congestion":"bbr","udpHop":{"ports":"20000-30000","interval":15}}}""")),
            )
        val fm = out.stream(0).finalmask()
        fm["quicParams"]!!.jsonObject shouldBe Json.parseToJsonElement("""{"congestion":"bbr"}""")
        fm["udp"] shouldBe
            Json.parseToJsonElement(
                """[{"type":"udphop","settings":{"mode":"intervalLocal,intervalRemote","interval":15,
                    "remotePorts":"20000-30000"}}]""",
            )
    }

    @Test fun `the mask goes last after the config's own masks, and an absent interval stays absent`() {
        val fm =
            """{"udp":[{"type":"salamander","settings":{"password":"hunter2"}},{"type":"noise","settings":{}}],
                "quicParams":{"udpHop":{"ports":"443,20000-30000"}}}"""
        val out = LegacyUdpHop.apply(parse(hysteria(fm)))
        out.stream(0).udpTypes() shouldBe listOf("salamander", "noise", "udphop")
        val hop = out.stream(0).finalmask()["udp"]!!.jsonArray[2].jsonObject["settings"]!!.jsonObject
        hop.keys shouldBe setOf("mode", "remotePorts")
        // quicParams had only udpHop: it goes with it rather than staying as an empty object.
        out.stream(0).finalmask().containsKey("quicParams") shouldBe false
    }

    @Test fun `an existing udphop mask wins and only the legacy key is dropped`() {
        val fm =
            """{"udp":[{"type":"udphop","settings":{"mode":"intervalLocal,intervalRemote","remotePorts":"5000-6000"}}],
                "quicParams":{"brutalUp":"20 mbps","udpHop":{"ports":"20000-30000"}}}"""
        val out = LegacyUdpHop.apply(parse(hysteria(fm)))
        out.stream(0).udpTypes() shouldBe listOf("udphop")
        out.stream(0).finalmask()["udp"]!!.jsonArray[0].jsonObject["settings"]!!.jsonObject["remotePorts"]!!
            .jsonPrimitive.content shouldBe "5000-6000"
        out.stream(0).finalmask()["quicParams"]!!.jsonObject.keys shouldBe setOf("brutalUp")
    }

    @Test fun `configs without the legacy shape, or not hysteria, or malformed, are returned unchanged`() {
        val plain = parse(hysteria("""{"quicParams":{"congestion":"bbr"}}"""))
        LegacyUdpHop.apply(plain) shouldBe plain
        val vless = parse("""[{"protocol":"vless","streamSettings":{"finalmask":{"quicParams":{"udpHop":{"ports":"1-2"}}}}}]""")
        LegacyUdpHop.apply(vless) shouldBe vless
        val noPorts = parse(hysteria("""{"quicParams":{"udpHop":{"interval":10}}}"""))
        LegacyUdpHop.apply(noPorts) shouldBe noPorts
        val udpNotArray = parse(hysteria("""{"udp":{},"quicParams":{"udpHop":{"ports":"1-2"}}}"""))
        LegacyUdpHop.apply(udpNotArray) shouldBe udpNotArray
    }

    @Test fun `composed with user noise the order is salamander, noise, udphop`() {
        val noise =
            NoiseSpec(listOf(NoiseItem(NoisePayload.Random(XrayRange(10, 20), XrayRange(0, 255)), XrayRange(10, 16))))
        val raw =
            """{"outbounds":${hysteria(
                """{"udp":[{"type":"salamander","settings":{"password":"hunter2"}}],
                    "quicParams":{"udpHop":{"ports":"20000-30000","interval":15}}}""",
            )}}"""
        val result =
            RawConfigComposer.compose(
                raw,
                settings.copy(obfuscation = Obfuscation(null, Applied(noise, replaceOwn = true))),
                "/assets",
                override = null,
            ) as ComposeResult.Ok
        val outbounds = Json.parseToJsonElement(result.json).jsonObject["outbounds"]!!.jsonArray
        outbounds.stream(0).udpTypes() shouldBe listOf("salamander", "noise", "udphop")
        outbounds.stream(0).finalmask().containsKey("quicParams") shouldBe false
    }
}
