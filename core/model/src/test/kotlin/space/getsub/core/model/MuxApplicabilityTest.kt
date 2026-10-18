// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

class MuxApplicabilityTest {
    private fun stream(network: String) = StreamSettings(network, Security.None)

    private val hy2Tls = Hysteria2Tls(serverName = "h")

    @Test
    fun vless() {
        MuxApplicability.applies(VlessOutbound("h", 443, "u", null, stream("tcp"))) shouldBe true
        MuxApplicability.applies(VlessOutbound("h", 443, "u", "", stream("ws"))) shouldBe true
        MuxApplicability.applies(VlessOutbound("h", 443, "u", "xtls-rprx-vision", stream("tcp"))) shouldBe false
        MuxApplicability.applies(VlessOutbound("h", 443, "u", null, stream("xhttp"))) shouldBe false
        MuxApplicability.applies(VlessOutbound("h", 443, "u", null, stream("XHTTP"))) shouldBe false
    }

    @Test
    fun vmessTrojanShadowsocks() {
        MuxApplicability.applies(VmessOutbound("h", 443, "u", 0, "auto", stream("grpc"))) shouldBe true
        MuxApplicability.applies(VmessOutbound("h", 443, "u", 0, "auto", stream("xhttp"))) shouldBe false
        MuxApplicability.applies(TrojanOutbound("h", 443, "p", stream("tcp"))) shouldBe true
        MuxApplicability.applies(TrojanOutbound("h", 443, "p", stream("splithttp"))) shouldBe false
        MuxApplicability.applies(ShadowsocksOutbound("h", 443, "aes-256-gcm", "p")) shouldBe true
    }

    @Test
    fun others() {
        MuxApplicability.applies(SocksOutbound("h", 1080, null, null)) shouldBe false
        MuxApplicability.applies(HttpOutbound("h", 8080, null, null, Security.None)) shouldBe false
        MuxApplicability.applies(Hysteria2Outbound("h", 443, "p", hy2Tls)) shouldBe false
        MuxApplicability.applies(
            WireGuardOutbound("h", 51820, "k", listOf("10.0.0.2/32"), "pk", null),
        ) shouldBe false
    }
}
