// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Tls
import space.getsub.core.model.KcpObfuscation
import space.getsub.core.model.ObfuscationApplicability
import space.getsub.core.model.Outbound
import space.getsub.core.model.Security
import space.getsub.core.model.ShadowsocksOutbound
import space.getsub.core.model.SocksOutbound
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.TrojanOutbound
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.VmessOutbound
import space.getsub.core.model.WireGuardOutbound
import space.getsub.core.model.isKcpNetwork

class ObfuscationDetectionTest {
    private fun outbounds(json: String): JsonArray = Json.parseToJsonElement(json).jsonArray

    private fun byTag(array: JsonArray): Map<String, JsonObject> =
        array.map { it.jsonObject }.associateBy { it["tag"].toString().trim('"') }

    @Test fun `only known dialling protocols are eligible`() {
        val array =
            outbounds(
                """[{"tag":"a","protocol":"vless"},{"tag":"d","protocol":"freedom"},
                    {"tag":"w","protocol":"wireguard"},{"tag":"b","protocol":"blackhole"}]""",
            )
        // M9-D2: WireGuard dials UDP itself and takes noise (research §1.3), so it is eligible.
        ObfuscationDetection.outermostEligible(array) shouldBe setOf(0, 2)
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

    private val bothChainKcp = bothChain.replace(""""streamSettings":{""", """"streamSettings":{"network":"kcp",""")

    @Test fun `plan of junk or a config without eligible outbounds is empty`() {
        ObfuscationDetection.plan("not json", user, user) shouldBe emptyList()
        ObfuscationDetection.plan("""{"outbounds":[{"tag":"d","protocol":"freedom"}]}""", user, user) shouldBe
            emptyList()
        // Two eligible outbounds dialling through each other: neither is outermost (case c).
        ObfuscationDetection.plan(
            """{"outbounds":[{"tag":"a","protocol":"vless","streamSettings":{"sockopt":{"dialerProxy":"b"}}},
                {"tag":"b","protocol":"vless","streamSettings":{"sockopt":{"dialerProxy":"a"}}}]}""",
            user,
            user,
        ) shouldBe emptyList()
    }

    // Noise is offered only to UDP-based servers, which are never offered the fragment (M9-E spec §7.2).
    @Test fun `noise On alone cuts an mkcp chain carrying both, and the chain's fragmentation is lost`() {
        ObfuscationDetection.plan(bothChainKcp, fragment = null, noise = user) shouldBe
            listOf(OutboundPlan(0, editable = true, cutLink = true, MaskFate.Nothing, MaskFate.Ours))
        ObfuscationDetection.plan(bothChainKcp, fragment = directive, noise = user) shouldBe
            listOf(OutboundPlan(0, editable = true, cutLink = true, MaskFate.Nothing, MaskFate.Ours))
    }

    @Test fun `noise On does not cut a tcp chain`() {
        ObfuscationDetection.plan(bothChain, fragment = null, noise = user) shouldBe
            listOf(OutboundPlan(0, editable = true, cutLink = false, MaskFate.Own, MaskFate.Own))
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
            listOf(OutboundPlan(0, editable = true, cutLink = false, MaskFate.Nothing, MaskFate.Nothing))
        val kcp = """{"outbounds":[{"tag":"p","protocol":"trojan","streamSettings":{"network":"kcp"}}]}"""
        ObfuscationDetection.plan(kcp, null, user) shouldBe
            listOf(OutboundPlan(0, editable = true, cutLink = false, MaskFate.Nothing, MaskFate.Ours))
    }

    @Test fun `an own finalmask mask survives a cut made for the other type`() {
        val json =
            """{"outbounds":[{"tag":"p","protocol":"vless","streamSettings":{"network":"kcp","sockopt":{"dialerProxy":"f"},
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

    // ── M9-D2 research §5.3: fragment is TCP, noise is UDP ──────────────────

    private fun planOf(outbound: String): OutboundPlan {
        val raw = """{"outbounds":[$outbound]}"""
        return ObfuscationDetection.plan(raw, user, user).single()
    }

    @Test fun `wireguard and mkcp take noise but never the fragment`() {
        val rows =
            listOf(
                """{"tag":"w","protocol":"wireguard","settings":{}}""",
                """{"tag":"k","protocol":"vless","streamSettings":{"network":"kcp"}}""",
                """{"tag":"k","protocol":"vmess","streamSettings":{"network":"mKCP","security":"tls"}}""",
            )
        for (row in rows) {
            val plan = planOf(row)
            plan.fragment shouldBe MaskFate.Nothing
            plan.noise shouldBe MaskFate.Ours
        }
    }

    @Test fun `http and httpupgrade take the fragment but never noise`() {
        val rows =
            listOf(
                """{"tag":"h","protocol":"http","settings":{"address":"p.example","port":8080}}""",
                """{"tag":"u","protocol":"vless","streamSettings":{"network":"httpupgrade","security":"tls"}}""",
                """{"tag":"u","protocol":"trojan","streamSettings":{"network":"HTTPUpgrade"}}""",
            )
        for (row in rows) {
            val plan = planOf(row)
            plan.fragment shouldBe MaskFate.Ours
            plan.noise shouldBe MaskFate.Nothing
        }
    }

    @Test fun `a config's own mask still reads as its own where ours is not offered`() {
        val ownNoise = """{"tag":"h","protocol":"http","streamSettings":{"finalmask":{"udp":[{"type":"noise"}]}}}"""
        planOf(ownNoise).noise shouldBe MaskFate.Own
        val ownFragment =
            """{"tag":"w","protocol":"wireguard","streamSettings":{"finalmask":{"tcp":[{"type":"fragment"}]}}}"""
        planOf(ownFragment).fragment shouldBe MaskFate.Own
    }

    @Test fun `tcp transports keep the fragment and are offered no noise`() {
        val plan = planOf("""{"tag":"v","protocol":"vless","streamSettings":{"network":"ws"}}""")
        plan.fragment shouldBe MaskFate.Ours
        plan.noise shouldBe MaskFate.Nothing
    }

    @Test fun `shadowsocks, socks and http are offered no noise even over a kcp network`() {
        for (protocol in listOf("shadowsocks", "socks", "http")) {
            val plan = planOf("""{"tag":"x","protocol":"$protocol","streamSettings":{"network":"kcp"}}""")
            plan.noise shouldBe MaskFate.Nothing
        }
    }

    // The raw twin of ObfuscationApplicability.noiseApplies (`carriesNoise`) must agree with it row by row.
    @Test fun `raw detection offers noise exactly where the typed rule applies it`() {
        val tls = Security.Tls("sni", "chrome", false)
        val kcp = TransportOptions.Kcp(null, null, KcpObfuscation(null, null, false))

        fun stream(n: String) = StreamSettings(n, tls, if (isKcpNetwork(n)) kcp else TransportOptions.None)
        val networks = listOf("tcp", "raw", "ws", "grpc", "httpupgrade", "xhttp", "kcp", "mkcp", "mKCP")
        val rows = mutableListOf<Pair<Outbound, String>>()
        for (n in networks) {
            rows += VlessOutbound("a", 1, "u", null, stream(n)) to "vless"
            rows += VmessOutbound("a", 1, "u", 0, "auto", stream(n)) to "vmess"
            rows += TrojanOutbound("a", 1, "p", stream(n)) to "trojan"
        }
        rows += Hysteria2Outbound("a", 1, "p", Hysteria2Tls("sni")) to "hysteria"
        rows += WireGuardOutbound("a", 1, "k", listOf("10.0.0.2/32"), "p", null) to "wireguard"
        rows += ShadowsocksOutbound("a", 1, "aes-256-gcm", "p") to "shadowsocks"
        rows += SocksOutbound("a", 1, null, null) to "socks"
        rows += HttpOutbound("a", 1, null, null, Security.None) to "http"
        for ((typed, protocol) in rows) {
            val network =
                when (typed) {
                    is VlessOutbound -> typed.stream.network
                    is VmessOutbound -> typed.stream.network
                    is TrojanOutbound -> typed.stream.network
                    else -> null
                }
            val streamJson = network?.let { ""","streamSettings":{"network":"$it"}""" }.orEmpty()
            val raw = """{"tag":"x","protocol":"$protocol"$streamJson}"""
            val offered = planOf(raw).noise != MaskFate.Nothing
            withClue("$protocol $network") { offered shouldBe ObfuscationApplicability.noiseApplies(typed) }
        }
    }
}
