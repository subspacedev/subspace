// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import space.getsub.core.model.Applied
import space.getsub.core.model.MuxSpec
import space.getsub.core.model.Outbound
import space.getsub.core.model.Profile
import space.getsub.core.model.Udp443Mode

/** M9-E spec §3: mux on the proxy outbound only, and only where the core can use it. */
class XrayConfigGeneratorMuxTest {
    private val base =
        TunnelSettings(
            socksPort = 10808,
            dnsServer = "1.1.1.1",
            enableSniffing = true,
            proxyCredentials = TEST_CREDENTIALS,
        )
    private val spec = MuxSpec(concurrency = 8, xudpConcurrency = 16, udp443 = Udp443Mode.Reject)
    private val muxOn = base.copy(mux = Applied(spec, replaceOwn = false))

    private fun json(
        outbound: Outbound,
        settings: TunnelSettings,
    ): String {
        val result = XrayConfigGenerator.generate(Profile(id = "id", name = "n", outbound = outbound), settings)
        return (result as ConfigResult.Ok).json
    }

    private fun golden(name: String) = checkNotNull(javaClass.getResource("/golden/$name")).readText().trimEnd()

    @Test fun `mux on a VMess server matches its golden`() {
        json(GOLDEN_VMESS, muxOn) shouldBe golden("vmess-ws-tls-mux.json")
    }

    @Test fun `mux on a VLESS Vision server writes no mux key`() {
        json(GOLDEN_OUTBOUND, muxOn) shouldBe golden("vless-reality.json")
    }

    @Test fun `mux off, or your switch On with mux off, leaves the goldens unchanged`() {
        json(GOLDEN_VMESS, base) shouldBe golden("vmess-ws-tls.json")
        json(GOLDEN_VMESS, base.copy(mux = Applied(null, replaceOwn = true))) shouldBe golden("vmess-ws-tls.json")
    }

    @Test fun `ineligible protocols never carry mux`() {
        json(GOLDEN_SOCKS, muxOn) shouldNotContain "\"mux\""
    }

    @Test fun `the wire values are written as given`() {
        val skip = base.copy(mux = Applied(MuxSpec(-1, 0, Udp443Mode.Skip), replaceOwn = true))
        json(GOLDEN_TROJAN, skip).contains(
            """"mux": {"enabled": true, "concurrency": -1, "xudpConcurrency": 0, "xudpProxyUDP443": "skip"},""",
        ) shouldBe true
        json(GOLDEN_SS, base.copy(mux = Applied(MuxSpec(1, 2, Udp443Mode.Allow), replaceOwn = false)))
            .contains(""""xudpProxyUDP443": "allow"""") shouldBe true
    }
}
