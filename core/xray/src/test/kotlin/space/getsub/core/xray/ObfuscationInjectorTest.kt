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
}
