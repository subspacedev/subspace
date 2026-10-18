// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test

/** M9-D2 research §5.3: fragment is a TCP mask, noise a UDP one; each is offered only where it runs. */
class ObfuscationApplicabilityTest {
    private val tls = Security.Tls("sni", "chrome", false)
    private val bareKcp = TransportOptions.Kcp(null, null, KcpObfuscation(null, null, false))

    private fun vless(
        network: String,
        transport: TransportOptions = TransportOptions.None,
    ) = VlessOutbound("a", 1, "u", null, StreamSettings(network, tls, transport))

    private val vmessKcp = VmessOutbound("a", 1, "u", 0, "auto", StreamSettings("kcp", Security.None, bareKcp))
    private val wireGuard = WireGuardOutbound("a", 1, "k", listOf("10.0.0.2/32"), "p", null)
    private val http = HttpOutbound("a", 1, null, null, Security.None)
    private val hysteria2 = Hysteria2Outbound("a", 1, "p", Hysteria2Tls("sni"))

    @Test fun `the applicability table`() {
        val rows =
            listOf(
                // outbound, fragment, noise
                Triple(vless("tcp"), true, false),
                Triple(vless("ws"), true, false),
                Triple(vless("kcp", bareKcp), false, true),
                Triple(vless("mKCP"), false, true),
                Triple(vless("httpupgrade", TransportOptions.HttpUpgrade("", "/")), true, false),
                Triple(vmessKcp, false, true),
                Triple(TrojanOutbound("a", 1, "p", StreamSettings("HTTPUpgrade", tls)), true, false),
                Triple(wireGuard, false, true),
                Triple(http, true, false),
                Triple(hysteria2, false, true),
                Triple(ShadowsocksOutbound("a", 1, "aes-256-gcm", "p"), true, false),
                Triple(SocksOutbound("a", 1, null, null), true, false),
            )
        for ((outbound, fragment, noise) in rows) {
            withClue(outbound.toString()) {
                ObfuscationApplicability.fragmentApplies(outbound) shouldBe fragment
                ObfuscationApplicability.noiseApplies(outbound) shouldBe noise
            }
        }
    }

    @Test fun `noise applies only to UDP-based servers`() {
        ObfuscationApplicability.noiseApplies(hysteria2) shouldBe true
        ObfuscationApplicability.noiseApplies(wireGuard) shouldBe true
        ObfuscationApplicability.noiseApplies(vless("kcp", bareKcp)) shouldBe true
        ObfuscationApplicability.noiseApplies(vless("mkcp", bareKcp)) shouldBe true
        ObfuscationApplicability.noiseApplies(vmessKcp) shouldBe true
        listOf("tcp", "raw", "ws", "grpc", "httpupgrade", "xhttp").forEach { n ->
            ObfuscationApplicability.noiseApplies(vless(n)) shouldBe false
            ObfuscationApplicability.noiseApplies(TrojanOutbound("a", 1, "p", StreamSettings(n, tls))) shouldBe false
        }
        ObfuscationApplicability.noiseApplies(ShadowsocksOutbound("a", 1, "aes-256-gcm", "p")) shouldBe false
        ObfuscationApplicability.noiseApplies(SocksOutbound("a", 1, null, null)) shouldBe false
        ObfuscationApplicability.noiseApplies(http) shouldBe false
    }

    @Test fun `applicableTo keeps only the masks that run`() {
        val fragment = Applied(FragmentSpec(FragmentPackets.TlsHello, XrayRange(1, 2), XrayRange(1, 2), null), false)
        val noise =
            Applied(
                NoiseSpec(listOf(NoiseItem(NoisePayload.Random(XrayRange(1, 2), XrayRange(0, 255)), XrayRange(1, 2)))),
                true,
            )
        val both = Obfuscation(fragment, noise)
        both.applicableTo(wireGuard) shouldBe Obfuscation(null, noise)
        both.applicableTo(http) shouldBe Obfuscation(fragment, null)
        both.applicableTo(vless("tcp")) shouldBe Obfuscation(fragment, null)
        both.applicableTo(vless("kcp", bareKcp)) shouldBe Obfuscation(null, noise)
    }
}
