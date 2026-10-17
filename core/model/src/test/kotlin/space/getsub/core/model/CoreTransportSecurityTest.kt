// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

class CoreTransportSecurityTest {
    private val plain = StreamSettings(network = "tcp", security = Security.None)
    private val tls = StreamSettings(network = "tcp", security = Security.Tls("s.example.com", "chrome", false))
    private val reality = StreamSettings(network = "tcp", security = Security.Reality("s", "k", "i", "chrome", "/"))

    private fun vless(
        address: String,
        stream: StreamSettings = plain,
    ) = VlessOutbound(address, 443, "11111111-2222-3333-4444-555555555555", null, stream)

    private fun trojan(
        address: String,
        stream: StreamSettings = plain,
    ) = TrojanOutbound(address, 443, "pw", stream)

    @Test
    fun vlessNoneToPublicDomainIsRefused() {
        vless("vpn.example.org").refusedWithoutTransportSecurity() shouldBe true
        vless("VPN.Example.ORG.").refusedWithoutTransportSecurity() shouldBe true
        // A lookalike suffix is not the suffix.
        vless("notlocal.org").refusedWithoutTransportSecurity() shouldBe true
        vless("a.mylocal").refusedWithoutTransportSecurity() shouldBe true
    }

    @Test
    fun vlessWithEncryptionIsAllowed() {
        val pq = "mlkem768x25519plus.native.0rtt.abc"
        vless("vpn.example.org").refusedWithoutTransportSecurity(vlessEncryption = pq) shouldBe false
        vless("vpn.example.org").refusedWithoutTransportSecurity(vlessEncryption = "") shouldBe true
        vless("vpn.example.org").refusedWithoutTransportSecurity(vlessEncryption = "none") shouldBe true
    }

    @Test
    fun vlessTlsOrRealityIsAllowed() {
        vless("vpn.example.org", tls).refusedWithoutTransportSecurity() shouldBe false
        vless("vpn.example.org", reality).refusedWithoutTransportSecurity() shouldBe false
        trojan("vpn.example.org", tls).refusedWithoutTransportSecurity() shouldBe false
    }

    @Test
    fun trojanNoneToPublicIpIsRefused() {
        trojan("203.0.114.1").refusedWithoutTransportSecurity() shouldBe true
        trojan("2001:db8::1").refusedWithoutTransportSecurity() shouldBe true
        trojan("1.1.1.1").refusedWithoutTransportSecurity() shouldBe true
    }

    @Test
    fun privateIpRangesAreAllowed() {
        val firstAddresses =
            listOf(
                "0.0.0.0",
                "10.0.0.0",
                "100.64.0.0",
                "127.0.0.0",
                "169.254.0.0",
                "172.16.0.0",
                "192.0.0.0",
                "192.0.2.0",
                "192.88.99.0",
                "192.168.0.0",
                "198.18.0.0",
                "198.51.100.0",
                "203.0.113.0",
                "224.0.0.0",
                "::",
                "fc00::",
                "fe80::",
                "ff00::",
                // The last address of a range, and the second of ::/127.
                "10.255.255.255",
                "172.31.255.255",
                "198.19.255.255",
                "255.255.255.255",
                "::1",
                "fdff::1",
                "[::1]",
            )
        for (address in firstAddresses) {
            vless(address).refusedWithoutTransportSecurity() shouldBe false
            trojan(address).refusedWithoutTransportSecurity() shouldBe false
        }
        // Just outside the edges.
        listOf("9.255.255.255", "172.32.0.0", "100.128.0.0", "198.20.0.0", "223.255.255.255", "::2", "fe00::")
            .forEach { vless(it).refusedWithoutTransportSecurity() shouldBe true }
    }

    @Test
    fun privateDomainSuffixesAndDotlessAreAllowed() {
        val allowed =
            listOf(
                "host.example",
                "a.local.",
                "router",
                "Router",
                "x.lan",
                "a.b.localdomain",
                "localhost",
                "x.invalid",
                "foo.test",
                "home.arpa",
                "nas.home.arpa",
                "svc.internal",
                "example",
                "a-b",
            )
        for (address in allowed) {
            vless(address).refusedWithoutTransportSecurity() shouldBe false
        }
        // Not dotless-matching: leading digit, trailing hyphen, over 63 characters.
        listOf("1host", "host-", "a".repeat(64)).forEach { vless(it).refusedWithoutTransportSecurity() shouldBe true }
    }

    @Test
    fun vmessAndShadowsocksAreNeverRefused() {
        VmessOutbound("vpn.example.org", 443, "u", 0, "auto", plain).refusedWithoutTransportSecurity() shouldBe false
        ShadowsocksOutbound("vpn.example.org", 443, "aes-256-gcm", "pw")
            .refusedWithoutTransportSecurity() shouldBe false
        SocksOutbound("vpn.example.org", 1080, null, null).refusedWithoutTransportSecurity() shouldBe false
    }
}
