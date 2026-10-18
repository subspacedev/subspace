// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * M9-D2 research §3.1: Xray v26.10.10 parses `kcpSettings.header`/`seed` and then never reads them, so a
 * classic raw mKCP config "builds" into bare KCP against a server expecting obfuscation. The composer
 * rewrites it into the `finalmask.udp` `mkcp-legacy` masks the core does read, in the order
 * `KcpRules.masks` writes for the typed path (research §3.3; ruling D2-R3 for header-only).
 */
class LegacyKcpTest {
    private val settings = TunnelSettings(socksPort = 41080, dnsServer = "1.1.1.1", enableSniffing = true)

    private val noise =
        NoiseSpec(listOf(NoiseItem(NoisePayload.Random(XrayRange(10, 20), XrayRange(0, 255)), XrayRange(10, 16))))

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonArray

    private fun JsonArray.stream(i: Int) = this[i].jsonObject["streamSettings"]!!.jsonObject

    private fun JsonObject.udp(): JsonElement? = (this["finalmask"] as? JsonObject)?.get("udp")

    private fun kcp(
        kcpSettings: String,
        extra: String = "",
        network: String = "kcp",
    ) = """[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"k.example","port":443,
            "users":[{"id":"u","encryption":"none"}]}]},
            "streamSettings":{"network":"$network","security":"tls","kcpSettings":$kcpSettings$extra}}]"""

    private fun masks(json: String): JsonElement = Json.parseToJsonElement(json)

    private val original = """{"type":"mkcp-legacy","settings":{}}"""

    private fun seed(s: String) = """{"type":"mkcp-legacy","settings":{"value":"$s"}}"""

    private fun header(h: String) = """{"type":"mkcp-legacy","settings":{"header":"$h"}}"""

    @Test fun `each classic shape becomes its masks and the inert keys go`() {
        val cases =
            listOf(
                """{"mtu":1350,"seed":"abc"}""" to "[${seed("abc")}]",
                """{"header":{"type":"wechat-video"}}""" to "[$original,${header("wechat")}]",
                """{"header":{"type":"srtp"},"seed":"abc"}""" to "[${seed("abc")},${header("srtp")}]",
                """{"header":{"type":"dtls"},"seed":"abc"}""" to "[${seed("abc")},${header("dtls")}]",
                // A string header is read too, as the projection reads it.
                """{"header":"utp"}""" to "[$original,${header("utp")}]",
                // D2-R6: whitespace is a real seed.
                """{"seed":" "}""" to "[${seed(" ")}]",
            )
        for ((kcpSettings, expected) in cases) {
            withClue(kcpSettings) {
                val stream = LegacyKcp.apply(parse(kcp(kcpSettings))).stream(0)
                stream.udp() shouldBe masks(expected)
                val written = stream["kcpSettings"]!!.jsonObject
                written.containsKey("header") shouldBe false
                written.containsKey("seed") shouldBe false
            }
        }
    }

    @Test fun `mtu and tti stay, and so does the rest of the stream`() {
        val stream = LegacyKcp.apply(parse(kcp("""{"mtu":1350,"tti":20,"seed":"abc"}"""))).stream(0)
        stream["kcpSettings"] shouldBe Json.parseToJsonElement("""{"mtu":1350,"tti":20}""")
        stream["security"]!!.jsonPrimitive.content shouldBe "tls"
        stream["network"]!!.jsonPrimitive.content shouldBe "kcp"
    }

    @Test fun `a none header and no seed is bare KCP, so no mask is added`() {
        val stream = LegacyKcp.apply(parse(kcp("""{"header":{"type":"none"},"seed":""}"""))).stream(0)
        stream.containsKey("finalmask") shouldBe false
        stream["kcpSettings"] shouldBe Json.parseToJsonElement("{}")
    }

    @Test fun `mkcp network spelling and an empty udp list are rewritten, a tcp half survives`() {
        val extra = ""","finalmask":{"tcp":[{"type":"fragment","settings":{}}],"udp":[]}"""
        val stream = LegacyKcp.apply(parse(kcp("""{"seed":"abc"}""", extra, network = "mKCP"))).stream(0)
        stream.udp() shouldBe masks("[${seed("abc")}]")
        stream["finalmask"]!!.jsonObject["tcp"]!!.jsonArray.size shouldBe 1
    }

    @Test fun `a config that already has masks, or nothing legacy, or an unknown header, is left as written`() {
        val already = parse(kcp("""{"seed":"abc"}""", ""","finalmask":{"udp":[${seed("xyz")}]}"""))
        LegacyKcp.apply(already) shouldBe already
        val modern = parse(kcp("""{"mtu":1350}"""))
        LegacyKcp.apply(modern) shouldBe modern
        // headerType=http has no mask; the projection marks the row not connectable.
        val http = parse(kcp("""{"header":{"type":"http"}}"""))
        LegacyKcp.apply(http) shouldBe http
        val unknown = parse(kcp("""{"header":{"type":"bogus"},"seed":"abc"}"""))
        LegacyKcp.apply(unknown) shouldBe unknown
        val ws = parse(kcp("""{"seed":"abc"}""", network = "ws"))
        LegacyKcp.apply(ws) shouldBe ws
        val udpNotArray = parse(kcp("""{"seed":"abc"}""", ""","finalmask":{"udp":{}}"""))
        LegacyKcp.apply(udpNotArray) shouldBe udpNotArray
        val finalmaskNotObject = parse(kcp("""{"seed":"abc"}""", ""","finalmask":[]"""))
        LegacyKcp.apply(finalmaskNotObject) shouldBe finalmaskNotObject
    }

    private val rawNoise = """{"type":"noise","settings":{"noise":[{"rand":"10-20","delay":"10-16"}]}}"""

    // Final review M3: noise is client-only junk (ruling D2-R12), so a udp list of only noise masks still means
    // "no mkcp masks yet". The legacy fields are translated and go in front of the noise.
    @Test fun `noise-only udp masks get the legacy masks in front, and the inert keys go`() {
        val extra = ""","finalmask":{"udp":[$rawNoise]}"""
        val stream = LegacyKcp.apply(parse(kcp("""{"header":{"type":"srtp"},"seed":"abc"}""", extra))).stream(0)
        stream.udp() shouldBe masks("[${seed("abc")},${header("srtp")},$rawNoise]")
        stream["kcpSettings"] shouldBe Json.parseToJsonElement("{}")
        LegacyKcp.apply(JsonArray(listOf(parse(kcp("{}", extra))[0]))).stream(0).udp() shouldBe masks("[$rawNoise]")
    }

    @Test fun `noise-only udp with a none header just loses the inert keys, and http stays as written`() {
        val extra = ""","finalmask":{"udp":[$rawNoise]}"""
        val bare = LegacyKcp.apply(parse(kcp("""{"header":{"type":"none"}}""", extra))).stream(0)
        bare.udp() shouldBe masks("[$rawNoise]")
        bare["kcpSettings"] shouldBe Json.parseToJsonElement("{}")
        val http = parse(kcp("""{"header":{"type":"http"}}""", extra))
        LegacyKcp.apply(http) shouldBe http
        val noiseThenMask = parse(kcp("""{"seed":"abc"}""", ""","finalmask":{"udp":[$rawNoise,$original]}"""))
        LegacyKcp.apply(noiseThenMask) shouldBe noiseThenMask
    }

    @Test fun `the rewrite is idempotent`() {
        val once = LegacyKcp.apply(parse(kcp("""{"header":{"type":"wechat-video"},"seed":"abc"}""")))
        LegacyKcp.apply(once) shouldBe once
    }

    @Test fun `the seed is escaped, never spliced`() {
        val evil = """a\",\"listen\":\"0.0.0.0"""
        val stream = LegacyKcp.apply(parse(kcp("""{"seed":"$evil"}"""))).stream(0)
        stream.udp()!!.jsonArray[0].jsonObject["settings"]!!.jsonObject["value"]!!.jsonPrimitive.content shouldBe
            "a\",\"listen\":\"0.0.0.0"
    }

    @Test fun `composed with user noise the order is seed, header, noise`() {
        val raw = """{"outbounds":${kcp("""{"header":{"type":"wechat-video"},"seed":"abc"}""")}}"""
        val result =
            RawConfigComposer.compose(
                raw,
                settings.copy(obfuscation = Obfuscation(null, Applied(noise, replaceOwn = true))),
                "/assets",
                override = null,
            ) as ComposeResult.Ok
        val stream = Json.parseToJsonElement(result.json).jsonObject["outbounds"]!!.jsonArray.stream(0)
        stream.udp()!!.jsonArray.map { it.jsonObject["settings"]!!.jsonObject.keys.firstOrNull() ?: "noise?" } shouldBe
            listOf("value", "header", "noise")
        stream.udp()!!.jsonArray.last().jsonObject["type"]!!.jsonPrimitive.content shouldBe "noise"
        stream["kcpSettings"]!!.jsonObject.containsKey("seed") shouldBe false
    }

    @Test fun `user noise on a raw kcp config with its own masks goes last, after the header`() {
        val raw = """{"outbounds":${kcp("{}", ""","finalmask":{"udp":[$original,${header("dns")}]}""")}}"""
        val result =
            RawConfigComposer.compose(
                raw,
                settings.copy(obfuscation = Obfuscation(null, Applied(noise, replaceOwn = false))),
                "/assets",
                override = null,
            ) as ComposeResult.Ok
        val udp = Json.parseToJsonElement(result.json).jsonObject["outbounds"]!!.jsonArray.stream(0).udp()!!.jsonArray
        udp.map { it.jsonObject["type"]!!.jsonPrimitive.content } shouldBe listOf("mkcp-legacy", "mkcp-legacy", "noise")
    }

    @Test fun `a header object without a type and a seed is the seed mask only`() {
        val stream = LegacyKcp.apply(parse(kcp("""{"header":{},"seed":"abc"}"""))).stream(0)
        stream.udp() shouldBe masks("[${seed("abc")}]")
        stream["kcpSettings"] shouldBe Json.parseToJsonElement("{}")
    }

    // Ruling D2-R11: `{header:"dns"}` without the domain makes the core use www.baidu.com, which changes the
    // header bytes and length on the wire, a silent timeout. The projection marks the row DnsHeaderDomain.
    @Test fun `a legacy dns header with a domain is left exactly as written`() {
        for (kcpSettings in listOf(
            """{"header":{"type":"dns","domain":"example.org"}}""",
            """{"header":{"type":"DNS","domain":"example.org"},"seed":"abc"}""",
        )) {
            val array = parse(kcp(kcpSettings))
            withClue(kcpSettings) { LegacyKcp.apply(array) shouldBe array }
        }
        // Without a domain (or with an empty one) the legacy default and the mask's default agree.
        val plain = LegacyKcp.apply(parse(kcp("""{"header":{"type":"dns","domain":""}}"""))).stream(0)
        plain.udp() shouldBe masks("[$original,${header("dns")}]")
    }
}
