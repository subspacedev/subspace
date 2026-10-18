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
import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.NoiseItem
import space.getsub.core.model.NoisePayload
import space.getsub.core.model.NoiseSpec
import space.getsub.core.model.Obfuscation
import space.getsub.core.model.XrayRange

class ObfuscationInjectorTest {
    private val frag = FragmentSpec(FragmentPackets.TlsHello, XrayRange(50, 100), XrayRange(10, 20), null)
    private val noise =
        NoiseSpec(listOf(NoiseItem(NoisePayload.Random(XrayRange(10, 20), XrayRange(0, 255)), XrayRange(10, 16))))

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonArray

    private fun JsonArray.at(i: Int) = this[i].jsonObject

    private fun JsonObject.masks(half: String): List<String> =
        ((this["streamSettings"]?.jsonObject?.get("finalmask") as? JsonObject)?.get(half) as? JsonArray)
            ?.map { it.jsonObject["type"]!!.jsonPrimitive.content }.orEmpty()

    private fun JsonObject.dialerProxy(): String? =
        (this["streamSettings"]?.jsonObject?.get("sockopt") as? JsonObject)?.get("dialerProxy")?.jsonPrimitive?.content

    private val chain =
        """[{"tag":"p","protocol":"vless","streamSettings":{"network":"tcp","sockopt":{"dialerProxy":"frag","mark":7},
              "finalmask":{"tcp":[{"type":"header-custom","settings":{}}]}}},
            {"tag":"frag","protocol":"freedom",
             "settings":{"fragment":{"packets":"tlshello","length":"1-2","interval":"1"}}},
            {"tag":"direct","protocol":"freedom"}]"""

    @Test fun `null or empty obfuscation returns the same array`() {
        val array = parse(chain)
        ObfuscationInjector.apply(array, null) shouldBe array
        ObfuscationInjector.apply(array, Obfuscation(null, null)) shouldBe array
    }

    @Test fun `directive mode leaves an outbound with its own fragment alone`() {
        val out = ObfuscationInjector.apply(parse(chain), Obfuscation(Applied(frag, replaceOwn = false), null))
        out.at(0).masks("tcp") shouldBe listOf("header-custom")
        out.at(0).dialerProxy() shouldBe "frag"
        out.at(0) shouldBe parse(chain).at(0)
    }

    @Test fun `user mode cuts the chain link, keeps the freedom outbound and puts ours first`() {
        val out = ObfuscationInjector.apply(parse(chain), Obfuscation(Applied(frag, replaceOwn = true), null))
        out.at(0).masks("tcp") shouldBe listOf("fragment", "header-custom")
        out.at(0).dialerProxy() shouldBe null
        // Other sockopt keys survive the cut.
        out.at(0)["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject["mark"]!!.jsonPrimitive.content shouldBe "7"
        out.size shouldBe 3
        out.at(1)["tag"]!!.jsonPrimitive.content shouldBe "frag"
    }

    @Test fun `user mode replaces an own finalmask fragment rather than stacking a second`() {
        val array =
            parse(
                """[{"tag":"p","protocol":"vless",
                    "streamSettings":{"finalmask":{"tcp":[{"type":"fragment","settings":{}}]}}}]""",
            )
        val out = ObfuscationInjector.apply(array, Obfuscation(Applied(frag, replaceOwn = true), null))
        out.at(0).masks("tcp") shouldBe listOf("fragment")
    }

    @Test fun `noise goes last in udp after the config's other masks`() {
        val array =
            parse(
                """[{"tag":"h","protocol":"hysteria",
                    "streamSettings":{"finalmask":{"udp":[{"type":"salamander","settings":{}}]}}}]""",
            )
        val out = ObfuscationInjector.apply(array, Obfuscation(null, Applied(noise, replaceOwn = false)))
        out.at(0).masks("udp") shouldBe listOf("salamander", "noise")
    }

    // Xray v26.10.10 transport/internet/finalmask/finalmask.go:147-154: a HandleDial() mask (udphop,
    // xicmp) must be last in JSON, else "incorrect index: N *udphop.Config" at dial time.
    @Test fun `noise lands before a hysteria outbound's own udphop`() {
        val array =
            parse(
                """[{"tag":"h","protocol":"hysteria","streamSettings":{"network":"hysteria","finalmask":{"udp":[
                    {"type":"salamander","settings":{"password":"pwpw"}},
                    {"type":"udphop","settings":{"mode":"intervalLocal,intervalRemote",
                     "remotePorts":"2000-3000"}}]}}}]""",
            )
        val out = ObfuscationInjector.apply(array, Obfuscation(null, Applied(noise, replaceOwn = false)))
        out.at(0).masks("udp") shouldBe listOf("salamander", "noise", "udphop")
    }

    @Test fun `user noise replacing an own noise also lands before udphop and xicmp`() {
        val array =
            parse(
                """[{"tag":"h","protocol":"hysteria","streamSettings":{"finalmask":{"udp":[
                    {"type":"udphop","settings":{}},{"type":"noise","settings":{}}]}}},
                   {"tag":"v","protocol":"vless",
                    "streamSettings":{"finalmask":{"udp":[{"type":"xicmp","settings":{}}]}}}]""",
            )
        val out = ObfuscationInjector.apply(array, Obfuscation(null, Applied(noise, replaceOwn = true)))
        out.at(0).masks("udp") shouldBe listOf("noise", "udphop")
        out.at(1).masks("udp") shouldBe listOf("noise", "xicmp")
    }

    @Test fun `fragment is not injected into a hysteria outbound`() {
        val array =
            parse(
                """[{"tag":"h","protocol":"hysteria","streamSettings":{"network":"hysteria"}},
                   {"tag":"q","protocol":"vless","streamSettings":{"network":"hysteria"}}]""",
            )
        val out = ObfuscationInjector.apply(array, Obfuscation(Applied(frag, replaceOwn = true), null))
        out shouldBe array
    }

    @Test fun `an outbound without streamSettings gains one`() {
        val array = parse("""[{"tag":"p","protocol":"trojan"}]""")
        val out = ObfuscationInjector.apply(array, Obfuscation(Applied(frag, replaceOwn = false), null))
        out.at(0).masks("tcp") shouldBe listOf("fragment")
    }

    @Test fun `inner hops, unknown protocols and malformed shapes are untouched`() {
        val array =
            parse(
                """[{"tag":"in","protocol":"vless","streamSettings":{"sockopt":{"dialerProxy":"out"}}},
                    {"tag":"out","protocol":"vless","streamSettings":{"finalmask":{"tcp":"oops"}}},
                    {"tag":"w","protocol":"wireguard"}]""",
            )
        val out = ObfuscationInjector.apply(array, Obfuscation(Applied(frag, replaceOwn = true), null))
        out.at(0) shouldBe array.at(0)
        out.at(1) shouldBe array.at(1)
        out.at(2) shouldBe array.at(2)
    }

    private fun freedomChain(
        freedomSettings: String,
        finalmask: String = "",
    ) = parse(
        """[{"tag":"p","protocol":"vless","streamSettings":{"sockopt":{"dialerProxy":"f"}$finalmask}},
            {"tag":"f","protocol":"freedom","settings":$freedomSettings}]""",
    )

    private val fragOnly = """{"fragment":{"packets":"tlshello","length":"1-2","interval":"1"}}"""
    private val both = """{"fragment":{"packets":"tlshello","length":"1-2","interval":"1"},"noises":[{"packet":"1"}]}"""
    private val noisesOnly = """{"noises":[{"packet":"1"}]}"""

    @Test fun `fragment user mode cut leaves a noise directive to fill its gap`() {
        val out = ObfuscationInjector.apply(freedomChain(both), Obfuscation(Applied(frag, true), Applied(noise, false)))
        out.at(0).dialerProxy() shouldBe null
        out.at(0).masks("tcp") shouldBe listOf("fragment")
        out.at(0).masks("udp").last() shouldBe "noise"
    }

    @Test fun `noise user mode cut leaves a fragment directive to fill its gap`() {
        val out = ObfuscationInjector.apply(freedomChain(both), Obfuscation(Applied(frag, false), Applied(noise, true)))
        out.at(0).dialerProxy() shouldBe null
        out.at(0).masks("tcp")[0] shouldBe "fragment"
        out.at(0).masks("udp") shouldBe listOf("noise")
    }

    @Test fun `noise user mode cuts a noises-only chain`() {
        val out = ObfuscationInjector.apply(freedomChain(noisesOnly), Obfuscation(null, Applied(noise, true)))
        out.at(0).dialerProxy() shouldBe null
        out.at(0).masks("udp") shouldBe listOf("noise")
        out.size shouldBe 2
    }

    @Test fun `a fragment directive keeps a chain that only carries noises`() {
        val out = ObfuscationInjector.apply(freedomChain(noisesOnly), Obfuscation(Applied(frag, false), null))
        out.at(0).dialerProxy() shouldBe "f"
        out.at(0).masks("tcp") shouldBe listOf("fragment")
    }

    @Test fun `own finalmask fragment plus fragmenting chain gives exactly one fragment in user mode`() {
        val array = freedomChain(fragOnly, ""","finalmask":{"tcp":[{"type":"fragment","settings":{}}]}""")
        val out = ObfuscationInjector.apply(array, Obfuscation(Applied(frag, true), null))
        out.at(0).masks("tcp") shouldBe listOf("fragment")
        out.at(0).dialerProxy() shouldBe null
    }

    @Test fun `an outbound where nothing applies is returned unchanged`() {
        val array = freedomChain(fragOnly)
        val out = ObfuscationInjector.apply(array, Obfuscation(Applied(frag, false), null))
        out.at(0) shouldBe array.at(0)
    }

    @Test fun `null streamSettings and null tcp read as absent`() {
        val a = parse("""[{"tag":"p","protocol":"trojan","streamSettings":null}]""")
        ObfuscationInjector.apply(a, Obfuscation(Applied(frag, false), null)).at(0).masks("tcp") shouldBe
            listOf("fragment")
        val b = parse("""[{"tag":"p","protocol":"trojan","streamSettings":{"finalmask":{"tcp":null}}}]""")
        ObfuscationInjector.apply(b, Obfuscation(Applied(frag, false), null)).at(0).masks("tcp") shouldBe
            listOf("fragment")
    }

    // ── M9-D2 research §5.3 ─────────────────────────────────────────────────

    @Test fun `a raw wireguard outbound gets user noise in finalmask udp and never the fragment`() {
        val array = parse("""[{"tag":"wg","protocol":"wireguard","settings":{"secretKey":"k","peers":[]}}]""")
        val out = ObfuscationInjector.apply(array, Obfuscation(Applied(frag, true), Applied(noise, true)))
        out.at(0).masks("udp") shouldBe listOf("noise")
        out.at(0).masks("tcp") shouldBe emptyList()
        out.at(0)["settings"] shouldBe array.at(0)["settings"]
    }

    @Test fun `raw mkcp gets no fragment, raw http and httpupgrade get no noise`() {
        val array =
            parse(
                """[{"tag":"k","protocol":"vless","streamSettings":{"network":"kcp"}},
                    {"tag":"h","protocol":"http","settings":{"address":"p.example","port":8080}},
                    {"tag":"u","protocol":"vless","streamSettings":{"network":"httpupgrade"}}]""",
            )
        val out = ObfuscationInjector.apply(array, Obfuscation(Applied(frag, true), Applied(noise, true)))
        out.at(0).masks("tcp") shouldBe emptyList()
        out.at(0).masks("udp") shouldBe listOf("noise")
        for (i in 1..2) {
            out.at(i).masks("tcp") shouldBe listOf("fragment")
            out.at(i).masks("udp") shouldBe emptyList()
        }
    }

    // Since WireGuard is eligible, it is the outermost hop of a vless-through-wireguard chain: only it is
    // offered masks, and as a UDP dial only noise.
    @Test fun `a vless dialling through wireguard leaves the wireguard hop with noise only`() {
        val array =
            parse(
                """[{"tag":"inner","protocol":"vless","streamSettings":{"network":"tcp","sockopt":{"dialerProxy":"wg"}}},
                    {"tag":"wg","protocol":"wireguard","settings":{"secretKey":"k","peers":[]}}]""",
            )
        val out = ObfuscationInjector.apply(array, Obfuscation(Applied(frag, true), Applied(noise, true)))
        out.at(0) shouldBe array.at(0)
        out.at(1).masks("udp") shouldBe listOf("noise")
        out.at(1).masks("tcp") shouldBe emptyList()
    }
}
