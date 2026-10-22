// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import space.getsub.core.model.Applied
import space.getsub.core.model.MuxSpec
import space.getsub.core.model.Udp443Mode

class MuxInjectorTest {
    private val spec = MuxSpec(concurrency = 8, xudpConcurrency = 16, udp443 = Udp443Mode.Reject)
    private val ours = Json.parseToJsonElement(
        """{"enabled":true,"concurrency":8,"xudpConcurrency":16,"xudpProxyUDP443":"reject"}""",
    )

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonArray

    private fun JsonArray.mux(i: Int) = this[i].jsonObject["mux"]

    private val own = """{"enabled":true,"concurrency":2}"""
    private val config =
        """[{"tag":"p","protocol":"vmess","mux":$own,"streamSettings":{"network":"ws"}},
            {"tag":"t","protocol":"trojan"},
            {"tag":"v","protocol":"vless","settings":{"vnext":[{"address":"a","port":1,
              "users":[{"id":"u","flow":"xtls-rprx-vision"}]}]}},
            {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}]"""

    @Test fun `null mux returns the same array`() {
        val array = parse(config)
        MuxInjector.apply(array, null) shouldBe array
    }

    @Test fun `a directive keeps the config's own mux and adds ours where there is none`() {
        val out = MuxInjector.apply(parse(config), Applied(spec, replaceOwn = false))
        out.mux(0) shouldBe Json.parseToJsonElement(own)
        out.mux(1) shouldBe ours
    }

    @Test fun `your setting replaces the config's own mux`() {
        val out = MuxInjector.apply(parse(config), Applied(spec, replaceOwn = true))
        out.mux(0) shouldBe ours
        out.mux(1) shouldBe ours
        // Everything else on the outbound is as written.
        (out[0] as JsonObject) - "mux" shouldBe (parse(config)[0] as JsonObject) - "mux"
    }

    @Test fun `your switch On with mux off removes the config's own mux`() {
        val out = MuxInjector.apply(parse(config), Applied(null, replaceOwn = true))
        out.mux(0) shouldBe null
        out.mux(1) shouldBe null
    }

    @Test fun `never added to freedom, blackhole or an ineligible outbound`() {
        val out = MuxInjector.apply(parse(config), Applied(spec, replaceOwn = true))
        out.mux(2) shouldBe null
        out.mux(3) shouldBe null
        out.mux(4) shouldBe null
        out.subList(2, 5) shouldBe parse(config).subList(2, 5)
    }

    @Test fun `the composer runs it on the config's own outbounds`() {
        val raw = """{"outbounds":[{"tag":"proxy","protocol":"trojan","settings":{}}]}"""
        val settings =
            TunnelSettings(
                socksPort = 10808,
                dnsServer = "1.1.1.1",
                enableSniffing = true,
                proxyCredentials = TEST_CREDENTIALS,
                mux = Applied(spec, replaceOwn = false),
            )
        val result = RawConfigComposer.compose(raw, settings, "/assets", override = null) as ComposeResult.Ok
        val outbounds = Json.parseToJsonElement(result.json).jsonObject["outbounds"]!!.jsonArray
        outbounds.mux(0) shouldBe ours
    }
}
