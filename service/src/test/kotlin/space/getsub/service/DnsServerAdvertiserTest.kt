// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.xray.DnsPlan
import space.getsub.core.xray.DnsServerSpec

class DnsServerAdvertiserTest {
    @Test
    fun `a rejected planned address is retried with the fallback`() {
        val added = mutableListOf<String>()
        val plannedAddress =
            DnsPlan(
                servers = listOf(DnsServerSpec("2001:db8::1")),
                hosts = emptyMap(),
                fakeDns = false,
                directMatch = null,
                proxyMatch = null,
            ).tunAdvertisedAddress()

        val usedFallback =
            addDnsServerOrFallback(
                plannedAddress = plannedAddress,
                fallbackAddress = "1.1.1.1",
            ) { address ->
                added += address
                require(address != "2001:db8::1") { "rejected" }
            }

        usedFallback shouldBe true
        added shouldBe listOf("2001:db8::1", "1.1.1.1")
    }

    // ── Lever 1 across a restart that keeps the TUN (spec §5.2) ─────────────
    //
    // What the TUN would advertise for a plan. Whether a restart may keep the
    // interface is now a comparison of the whole planned shape, pinned in
    // TunShapeTest (retainedTunKeepsShape), with the advertised-resolver cases
    // that used to live here ported there unchanged. Neither file covers the
    // wiring in TunnelService.restartCoreRetainingTun/attachTun: a
    // VpnService.Builder cannot be constructed off-device, and device row W8 in
    // docs/agent/research/2026-09-07-m8-device-verification.md is the debt (§10.1).

    private fun planAdvertising(address: String) =
        DnsPlan(
            servers = listOf(DnsServerSpec(address)),
            hosts = emptyMap(),
            fakeDns = false,
            directMatch = null,
            proxyMatch = null,
        )

    @Test
    fun `a plan with no literal to advertise resolves to the fallback`() {
        advertisedTunDnsAddress(null, "1.1.1.1") shouldBe "1.1.1.1"
    }

    @Test
    fun `a plan with a literal resolves to it`() {
        advertisedTunDnsAddress(planAdvertising("77.88.8.8").tunAdvertisedAddress(), "1.1.1.1") shouldBe "77.88.8.8"
    }
}
