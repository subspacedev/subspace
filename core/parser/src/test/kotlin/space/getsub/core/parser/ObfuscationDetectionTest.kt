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

    private val user = MaskOffer(replaceOwn = true)
    private val directive = MaskOffer(replaceOwn = false)

    private val bothChain =
        """{"outbounds":[{"tag":"p","protocol":"vless","streamSettings":{"sockopt":{"dialerProxy":"f"}}},
            {"tag":"f","protocol":"freedom",
             "settings":{"fragment":{"packets":"tlshello"},"noises":[{"packet":"1"}]}}]}"""

    @Test fun `plan of junk or a config without eligible outbounds is empty`() {
        ObfuscationDetection.plan("not json", user, user) shouldBe emptyList()
        ObfuscationDetection.plan("""{"outbounds":[{"tag":"w","protocol":"wireguard"}]}""", user, user) shouldBe
            emptyList()
        // Two eligible outbounds dialling through each other: neither is outermost (case c).
        ObfuscationDetection.plan(
            """{"outbounds":[{"tag":"a","protocol":"vless","streamSettings":{"sockopt":{"dialerProxy":"b"}}},
                {"tag":"b","protocol":"vless","streamSettings":{"sockopt":{"dialerProxy":"a"}}}]}""",
            user,
            user,
        ) shouldBe emptyList()
    }

    @Test fun `noise On alone cuts a chain carrying both, and the chain's fragmentation is lost`() {
        ObfuscationDetection.plan(bothChain, fragment = null, noise = user) shouldBe
            listOf(OutboundPlan(0, editable = true, cutLink = true, MaskFate.Nothing, MaskFate.Ours))
        ObfuscationDetection.plan(bothChain, fragment = directive, noise = user) shouldBe
            listOf(OutboundPlan(0, editable = true, cutLink = true, MaskFate.Ours, MaskFate.Ours))
    }

    @Test fun `fragmentation On alone cuts a chain carrying both, and the chain's noise is lost`() {
        ObfuscationDetection.plan(bothChain, fragment = user, noise = null) shouldBe
            listOf(OutboundPlan(0, editable = true, cutLink = true, MaskFate.Ours, MaskFate.Nothing))
    }

    @Test fun `directives never displace a config's own masks`() {
        ObfuscationDetection.plan(bothChain, directive, directive) shouldBe
            listOf(OutboundPlan(0, editable = true, cutLink = false, MaskFate.Own, MaskFate.Own))
        ObfuscationDetection.plan(bothChain, null, null) shouldBe
            listOf(OutboundPlan(0, editable = true, cutLink = false, MaskFate.Own, MaskFate.Own))
    }

    @Test fun `plain outbounds get the offered mask and nothing else`() {
        val plain = """{"outbounds":[{"tag":"p","protocol":"trojan"},{"tag":"d","protocol":"freedom"}]}"""
        ObfuscationDetection.plan(plain, directive, null) shouldBe
            listOf(OutboundPlan(0, editable = true, cutLink = false, MaskFate.Ours, MaskFate.Nothing))
        ObfuscationDetection.plan(plain, null, user) shouldBe
            listOf(OutboundPlan(0, editable = true, cutLink = false, MaskFate.Nothing, MaskFate.Ours))
    }

    @Test fun `an own finalmask mask survives a cut made for the other type`() {
        val json =
            """{"outbounds":[{"tag":"p","protocol":"vless","streamSettings":{"sockopt":{"dialerProxy":"f"},
                "finalmask":{"tcp":[{"type":"fragment"}]}}},
                {"tag":"f","protocol":"freedom","settings":{"noises":[{"packet":"1"}]}}]}"""
        ObfuscationDetection.plan(json, null, user) shouldBe
            listOf(OutboundPlan(0, editable = true, cutLink = true, MaskFate.Own, MaskFate.Ours))
    }

    @Test fun `a malformed finalmask runs as written, own masks included`() {
        val json =
            """{"outbounds":[{"tag":"p","protocol":"vless",
                "streamSettings":{"sockopt":{"dialerProxy":"f"},"finalmask":{"tcp":"oops"}}},
                {"tag":"f","protocol":"freedom","settings":{"fragment":{}}}]}"""
        val plan = ObfuscationDetection.plan(json, user, user).single()
        plan shouldBe OutboundPlan(0, editable = false, cutLink = false, MaskFate.Own, MaskFate.Nothing)
        plan.touched shouldBe false
    }

    @Test fun `a malformed streamSettings is not an own mask and does not throw`() {
        val array = outbounds("""[{"tag":"p","protocol":"vless","streamSettings":"oops"}]""")
        ObfuscationDetection.ownFragment(array[0].jsonObject, byTag(array)).present shouldBe false
    }
}
