// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Test
import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Tls
import space.getsub.core.model.MuxApplicability
import space.getsub.core.model.MuxSpec
import space.getsub.core.model.Outbound
import space.getsub.core.model.Security
import space.getsub.core.model.ShadowsocksOutbound
import space.getsub.core.model.SocksOutbound
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TrojanOutbound
import space.getsub.core.model.Udp443Mode
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.VmessOutbound
import space.getsub.core.model.WireGuardOutbound

class MuxDetectionTest {
    private val yoursOn = MuxOffer(on = true, replaceOwn = true)
    private val yoursOff = MuxOffer(on = false, replaceOwn = true)
    private val providerOn = MuxOffer(on = true, replaceOwn = false)

    private fun plans(
        outbounds: String,
        offer: MuxOffer?,
    ) = MuxDetection.plan(Json.parseToJsonElement(outbounds).jsonArray, offer)

    private val vmess = """{"tag":"p","protocol":"vmess","streamSettings":{"network":"ws"}}"""
    private val vmessOwn =
        """{"tag":"p","protocol":"vmess","mux":{"enabled":true,"concurrency":4,"xudpProxyUDP443":"skip"}}"""

    @Test fun `an eligible outbound without its own mux takes any offer`() {
        plans("[$vmess]", providerOn).single().fate shouldBe MuxFate.Ours
        plans("[$vmess]", yoursOn).single().fate shouldBe MuxFate.Ours
        plans("[$vmess]", null).single().fate shouldBe MuxFate.Nothing
        plans("[$vmess]", yoursOff).single().fate shouldBe MuxFate.Nothing
    }

    @Test fun `a config's own mux is kept against a directive and replaced by yours`() {
        plans("[$vmessOwn]", null).single().fate shouldBe MuxFate.Own
        plans("[$vmessOwn]", providerOn).single().fate shouldBe MuxFate.Own
        plans("[$vmessOwn]", yoursOn).single().fate shouldBe MuxFate.Ours
        plans("[$vmessOwn]", yoursOff).single().fate shouldBe MuxFate.Removed
    }

    @Test fun `the own block is read as the core reads it`() {
        plans("[$vmessOwn]", null).single().ownSpec shouldBe MuxSpec(4, 0, Udp443Mode.Skip)
        plans("""[{"protocol":"vmess","mux":{"enabled":false,"concurrency":4}}]""", null).single().ownSpec shouldBe
            null
        plans("[$vmess]", null).single().ownSpec shouldBe null
    }

    @Test fun `freedom, blackhole, dns and inner hops are never planned`() {
        val array =
            """[{"tag":"inner","protocol":"vmess","streamSettings":{"sockopt":{"dialerProxy":"outer"}}},
                {"tag":"outer","protocol":"trojan"},
                {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"},
                {"tag":"dns-out","protocol":"dns"}]"""
        plans(array, yoursOn).map { it.index } shouldBe listOf(1)
    }

    @Test fun `an ineligible outbound keeps its own mux and is never given one`() {
        val vision =
            """{"protocol":"vless","settings":{"vnext":[{"address":"a","port":1,
                "users":[{"id":"u","flow":"xtls-rprx-vision"}]}]},"mux":{"enabled":true}}"""
        val plan = plans("[$vision]", yoursOn).single()
        plan.eligible shouldBe false
        plan.fate shouldBe MuxFate.Own
        plans("""[{"protocol":"socks"}]""", yoursOn).single().fate shouldBe MuxFate.Nothing
    }

    @Test fun `a VLESS flow is found in either settings shape`() {
        val flat =
            """{"protocol":"vless","settings":{"address":"a","port":1,"id":"u","flow":"xtls-rprx-vision-udp443"}}"""
        val plain = """{"protocol":"vless","settings":{"address":"a","port":1,"id":"u","flow":""}}"""
        plans("[$flat]", yoursOn).single().eligible shouldBe false
        plans("[$plain]", yoursOn).single().eligible shouldBe true
    }

    @Test fun `junk plans nothing`() {
        MuxDetection.plan("not json", yoursOn) shouldBe emptyList()
        MuxDetection.plan("""{"outbounds":{}}""", yoursOn) shouldBe emptyList()
    }

    // The raw twin of MuxApplicability.applies (`carriesMux`) must agree with it row by row.
    @Test fun `raw detection offers mux exactly where the typed rule applies it`() {
        val tls = Security.Tls("sni", "chrome", false)
        val networks = listOf("tcp", "raw", "ws", "grpc", "httpupgrade", "kcp", "xhttp", "splithttp", "XHTTP")
        val rows = mutableListOf<Pair<Outbound, String>>()
        for (n in networks) {
            for (flow in listOf(null, "xtls-rprx-vision", "xtls-rprx-vision-udp443")) {
                rows += VlessOutbound("a", 1, "u", flow, StreamSettings(n, tls)) to "vless"
            }
            rows += VmessOutbound("a", 1, "u", 0, "auto", StreamSettings(n, tls)) to "vmess"
            rows += TrojanOutbound("a", 1, "p", StreamSettings(n, tls)) to "trojan"
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
            val flow = (typed as? VlessOutbound)?.flow
            val streamJson = network?.let { ""","streamSettings":{"network":"$it"}""" }.orEmpty()
            val settingsJson =
                flow
                    ?.let { ""","settings":{"vnext":[{"address":"a","port":1,"users":[{"id":"u","flow":"$it"}]}]}""" }
                    .orEmpty()
            val raw = """[{"tag":"x","protocol":"$protocol"$settingsJson$streamJson}]"""
            val offered = plans(raw, yoursOn).single().fate == MuxFate.Ours
            withClue("$protocol $network $flow") { offered shouldBe MuxApplicability.applies(typed) }
        }
    }
}
