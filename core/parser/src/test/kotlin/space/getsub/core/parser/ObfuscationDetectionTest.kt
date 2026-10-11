// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ObfuscationDetectionTest {
    private fun outbounds(json: String): JsonArray = Json.parseToJsonElement(json).jsonArray

    private fun byTag(array: JsonArray): Map<String, JsonObject> =
        array.map { it.jsonObject }.associateBy { it["tag"].toString().trim('"') }

    @Test fun `only known dialling protocols are eligible`() {
        val array =
            outbounds(
                """[{"tag":"a","protocol":"vless"},{"tag":"d","protocol":"freedom"},
                    {"tag":"w","protocol":"wireguard"}]""",
            )
        ObfuscationDetection.outermostEligible(array) shouldBe setOf(0)
    }

    @Test fun `an outbound dialling through another proxy outbound is not the outermost hop`() {
        val array =
            outbounds(
                """[{"tag":"inner","protocol":"vless","streamSettings":{"sockopt":{"dialerProxy":"outer"}}},
                    {"tag":"outer","protocol":"trojan"}]""",
            )
        ObfuscationDetection.outermostEligible(array) shouldBe setOf(1)
    }

    @Test fun `dialling through a freedom outbound still counts as outermost`() {
        val array =
            outbounds(
                """[{"tag":"p","protocol":"vless","streamSettings":{"sockopt":{"dialerProxy":"frag"}}},
                    {"tag":"frag","protocol":"freedom",
                     "settings":{"fragment":{"packets":"tlshello","length":"100-200","interval":"10-20"}}}]""",
            )
        ObfuscationDetection.outermostEligible(array) shouldBe setOf(0)
        val p = array[0].jsonObject
        ObfuscationDetection.ownFragment(p, byTag(array)) shouldBe OwnMask(inFinalmask = false, freedomTag = "frag")
        ObfuscationDetection.ownNoise(p, byTag(array)) shouldBe OwnMask(inFinalmask = false, freedomTag = null)
    }

    @Test fun `finalmask entries are detected by type`() {
        val array =
            outbounds(
                """[{"tag":"p","protocol":"hysteria","streamSettings":{"finalmask":{
                    "tcp":[{"type":"fragment"}],"udp":[{"type":"salamander"},{"type":"noise"}]}}}]""",
            )
        val p = array[0].jsonObject
        ObfuscationDetection.ownFragment(p, byTag(array)).present shouldBe true
        ObfuscationDetection.ownNoise(p, byTag(array)) shouldBe OwnMask(inFinalmask = true, freedomTag = null)
    }

    @Test fun `configOwn answers for the whole config, and false for junk`() {
        ObfuscationDetection.configOwn(
            """{"outbounds":[{"tag":"p","protocol":"vless",
                "streamSettings":{"finalmask":{"tcp":[{"type":"fragment"}]}}}]}""",
        ) shouldBe OwnMasks(fragment = true, noise = false)
        ObfuscationDetection.configOwn("not json") shouldBe OwnMasks(fragment = false, noise = false)
    }

    @Test fun `a malformed streamSettings is not an own mask and does not throw`() {
        val array = outbounds("""[{"tag":"p","protocol":"vless","streamSettings":"oops"}]""")
        ObfuscationDetection.ownFragment(array[0].jsonObject, byTag(array)).present shouldBe false
    }
}
