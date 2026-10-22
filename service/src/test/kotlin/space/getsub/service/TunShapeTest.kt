// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import space.getsub.core.data.ResolvedTuning
import space.getsub.core.data.TuningValue
import space.getsub.core.model.CidrRange
import space.getsub.core.model.ExcludedRoutes
import space.getsub.core.model.TuningChecked
import space.getsub.core.model.TuningOrigin
import space.getsub.core.xray.DnsPlan
import space.getsub.core.xray.DnsServerSpec
import java.math.BigInteger
import java.net.InetAddress

/**
 * The planned TUN shape (M9-E spec §4, §7.1) and the route plan built from it.
 *
 * Like [DnsServerAdvertiserTest], these pin decisions, not the wiring: `VpnService.Builder` cannot be
 * constructed off-device, so whether `TunnelService.establishTun` hands the plan to it is device rows
 * 6 and 7's to show (§10.1).
 */
class TunShapeTest {
    private fun ranges(text: String) = (ExcludedRoutes.parse(text) as TuningChecked.Ok).value

    private fun shape(
        mtu: Int = 8500,
        dns: String = "1.1.1.1",
        excluded: String = "",
    ) = TunShape(mtu, dns, ranges(excluded))

    // ── routePlanFor, API 33+ ───────────────────────────────────────────────

    @Test
    fun `API 33 and later excludes the ranges themselves`() {
        val list = ranges("192.168.0.0/16 10.0.0.0/8 2001:db8::/32")
        routePlanFor(list, 33) shouldBe RoutePlan.Exclude(list)
        routePlanFor(list, 35) shouldBe RoutePlan.Exclude(list)
        routePlanFor(emptyList(), 33) shouldBe RoutePlan.Exclude(emptyList())
    }

    @Test
    fun `an Exclude plan still adds both default routes before its exclusions`() {
        val calls = mutableListOf<String>()
        applyRoutePlan(
            RoutePlan.Exclude(ranges("10.0.0.0/8 fd12::/16")),
            addRoute = { address, prefix -> calls += "add $address/$prefix" },
            excludeRoute = { calls += "exclude $it" },
        )
        calls shouldContainExactly listOf("add 0.0.0.0/0", "add ::/0", "exclude 10.0.0.0/8", "exclude fd12::/16")
    }

    @Test
    fun `an Add plan adds exactly its routes`() {
        val calls = mutableListOf<String>()
        applyRoutePlan(
            routePlanFor(emptyList(), 32),
            addRoute = { address, prefix -> calls += "add $address/$prefix" },
            excludeRoute = { calls += "exclude $it" },
        )
        // No exclusions below 33 is exactly today's interface.
        calls shouldContainExactly listOf("add 0.0.0.0/0", "add ::/0")
    }

    /**
     * Android's `Builder.addRoute`/`excludeRoute` refuse a loopback address, and a v4-mapped IPv6
     * literal parses as an `Inet4Address` whose prefix then fails the IPv4 bound. Neither block is ever
     * routed through a VPN, so a range inside one is dropped rather than handed to the builder.
     */
    @Test
    fun `ranges Android cannot express are dropped from an Exclude plan`() {
        routePlanFor(ranges("127.0.0.0/8 127.1.2.3 ::1 ::ffff:10.0.0.0/104 10.0.0.0/8"), 33) shouldBe
            RoutePlan.Exclude(ranges("10.0.0.0/8"))
    }

    // ── routePlanFor, below API 33 (the complement) ─────────────────────────

    @Test
    fun `below API 33 the routes miss every listed range and keep the rest`() {
        val list = ranges("10.0.0.0/8 192.168.1.0/24 8.8.4.4 2001:db8::/32")
        val routes = addRoutes(list)
        val dropped = listOf("10.0.0.1", "10.255.255.255", "192.168.1.77", "8.8.4.4", "2001:db8::1", "2001:db8:ffff::")
        for (probe in dropped) {
            inAny(routes, probe) shouldBe false
        }
        val kept = listOf("9.255.255.255", "11.0.0.0", "192.168.2.1", "8.8.8.8", "1.1.1.1", "2001:db9::", "::2")
        for (probe in kept) {
            inAny(routes, probe) shouldBe true
        }
        assertPartition(list, routes)
    }

    @Test
    fun `overlapping and nested ranges are covered once`() {
        // 10.1.0.0/16 sits inside 10.0.0.0/8; 10.0.0.0/8 and 10.0.0.0/9 overlap; duplicates fold.
        val list = ranges("10.0.0.0/8 10.1.0.0/16 10.0.0.0/9 10.200.0.0/16 2001:db8::/32 2001:db8:1::/48")
        val routes = addRoutes(list)
        inAny(routes, "10.1.2.3") shouldBe false
        inAny(routes, "10.200.0.1") shouldBe false
        inAny(routes, "2001:db8:1::5") shouldBe false
        assertPartition(list, routes)
        // Nesting changes nothing: the plan is the outer range's alone.
        routes shouldBe addRoutes(ranges("10.0.0.0/8 2001:db8::/32"))
    }

    @Test
    fun `adjacent ranges leave no gap and no sliver between them`() {
        val list = ranges("192.168.0.0/24 192.168.1.0/24 fd00:a::/32 fd00:b::/32")
        val routes = addRoutes(list)
        inAny(routes, "192.168.0.255") shouldBe false
        inAny(routes, "192.168.1.0") shouldBe false
        inAny(routes, "192.168.2.0") shouldBe true
        inAny(routes, "192.167.255.255") shouldBe true
        inAny(routes, "fd00:a:ffff::") shouldBe false
        inAny(routes, "fd00:b::") shouldBe false
        inAny(routes, "fd00:c::") shouldBe true
        assertPartition(list, routes)
    }

    @Test
    fun `IPv6 membership is exact at the range edges`() {
        val list = ranges("2001:db8::/127 fe80::/10")
        val routes = addRoutes(list)
        inAny(routes, "2001:db8::") shouldBe false
        inAny(routes, "2001:db8::1") shouldBe false
        inAny(routes, "2001:db8::2") shouldBe true
        inAny(routes, "2001:db7:ffff:ffff:ffff:ffff:ffff:ffff") shouldBe true
        inAny(routes, "fe80::1") shouldBe false
        inAny(routes, "febf:ffff::") shouldBe false
        inAny(routes, "fec0::") shouldBe true
        // A v6-only list leaves IPv4 whole: one 0.0.0.0/0 route.
        routes.filter { !it.isV6 } shouldBe listOf(CidrRange("0.0.0.0", 0, false))
        assertPartition(list, routes)
    }

    @Test
    fun `a v4-only list leaves IPv6 as one default route`() {
        addRoutes(ranges("10.0.0.0/8")).filter { it.isV6 } shouldBe listOf(CidrRange("::", 0, true))
    }

    /** 126.0.0.0/8's complement would otherwise contain the route 127.0.0.0/8, which addRoute throws on. */
    @Test
    fun `the complement never names a loopback or v4-mapped block`() {
        val routes = addRoutes(ranges("126.0.0.0/8 ::/128 ::fffe:0:0/96 ::ffff:1.2.3.4"))
        routes.none { inBlock(it, "127.0.0.1") || inBlock(it, "::1") } shouldBe true
        routes.none { it.isV6 && overlaps(it, CidrRange("::ffff:0:0", 96, true)) } shouldBe true
        assertPartition(ranges("126.0.0.0/8 ::/128 ::fffe:0:0/96"), routes)
    }

    @Test
    fun `a list of only unroutable ranges is today's interface`() {
        routePlanFor(ranges("127.0.0.1 ::1"), 26) shouldBe
            RoutePlan.Add(listOf(CidrRange("0.0.0.0", 0, false), CidrRange("::", 0, true)))
    }

    // ── retainedTunKeepsShape ───────────────────────────────────────────────

    @Test
    fun `equal shapes keep the interface`() {
        retainedTunKeepsShape(shape(excluded = "10.0.0.0/8"), shape(excluded = "10.0.0.0/8")) shouldBe true
    }

    @Test
    fun `a changed MTU alone rebuilds the interface`() {
        retainedTunKeepsShape(shape(mtu = 8500), shape(mtu = 1400)) shouldBe false
    }

    @Test
    fun `a changed excluded list alone rebuilds the interface`() {
        retainedTunKeepsShape(shape(excluded = "10.0.0.0/8"), shape(excluded = "10.0.0.0/8 192.168.0.0/16")) shouldBe
            false
        retainedTunKeepsShape(shape(excluded = "10.0.0.0/8"), shape()) shouldBe false
    }

    // Ported from retainedTunKeepsAdvertisedDns (ARCHITECTURE.md §5.2, lever 1): the advertised resolver
    // is still part of the shape, so its cases hold unchanged.

    @Test
    fun `an unchanged advertised address keeps the interface`() {
        val pinned = advertisedTunDnsAddress(planAdvertising("77.88.8.8").tunAdvertisedAddress(), "1.1.1.1")
        val next = advertisedTunDnsAddress(planAdvertising("77.88.8.8").tunAdvertisedAddress(), "1.1.1.1")
        retainedTunKeepsShape(shape(dns = pinned), shape(dns = next)) shouldBe true
    }

    /** DNS off mid-session: the new config has no port-53 hijack while the TUN advertises the old resolver. */
    @Test
    fun `turning DNS off mid-session rebuilds the interface`() {
        val pinned = advertisedTunDnsAddress(planAdvertising("77.88.8.8").tunAdvertisedAddress(), "1.1.1.1")
        val next = advertisedTunDnsAddress(null, "1.1.1.1")
        retainedTunKeepsShape(shape(dns = pinned), shape(dns = next)) shouldBe false
    }

    @Test
    fun `turning DNS on mid-session rebuilds the interface`() {
        val pinned = advertisedTunDnsAddress(null, "1.1.1.1")
        val next = advertisedTunDnsAddress(planAdvertising("77.88.8.8").tunAdvertisedAddress(), "1.1.1.1")
        retainedTunKeepsShape(shape(dns = pinned), shape(dns = next)) shouldBe false
    }

    /** Nothing recorded means unknown, not unchanged: rebuild rather than guess. */
    @Test
    fun `an unrecorded shape rebuilds the interface`() {
        retainedTunKeepsShape(null, shape()) shouldBe false
    }

    // ── Where the shape comes from ──────────────────────────────────────────

    @Test
    fun `the session's DNS addresses are the advertised one and every plain resolver literal`() {
        val plan =
            DnsPlan(
                servers =
                listOf(
                    DnsServerSpec("77.88.8.8", domains = listOf("domain:example.ru"), skipFallback = true),
                    DnsServerSpec("https://dns.example/dns-query"),
                    DnsServerSpec("2001:db8::53"),
                ),
                hosts = mapOf("dns.example" to "9.9.9.9"),
                fakeDns = false,
                directMatch = null,
                proxyMatch = null,
            )
        sessionDnsAddresses(plan, "77.88.8.8", fallbackDns = "77.88.8.8") shouldBe listOf("77.88.8.8", "2001:db8::53")
        sessionDnsAddresses(null, "1.1.1.1", fallbackDns = "1.1.1.1") shouldBe listOf("1.1.1.1")
    }

    @Test
    fun `the builder's fallback resolver is a session DNS address when it differs from the planned one`() {
        // addDnsServerOrFallback advertises the fallback if the builder rejects the planned literal,
        // so a range covering the fallback would let those queries leave outside the tunnel.
        sessionDnsAddresses(null, "77.88.8.8", fallbackDns = "1.1.1.1") shouldBe listOf("77.88.8.8", "1.1.1.1")
    }

    @Test
    fun `the shape carries the resolved MTU and ranges`() {
        val tuning =
            ResolvedTuning(
                sniffing = TuningValue(true, TuningOrigin.Default),
                mux = TuningValue(null, TuningOrigin.Default),
                tunMtu = TuningValue(1400, TuningOrigin.Yours),
                excludedRoutes = TuningValue(ranges("10.0.0.0/8"), TuningOrigin.Yours),
            )
        tunShapeFor(tuning, "1.1.1.1") shouldBe TunShape(1400, "1.1.1.1", ranges("10.0.0.0/8"))
    }

    /** ARCHITECTURE.md §5.6: the resolver and the user's LAN layout never reach a log line. */
    @Test
    fun `toString names no address`() {
        val text = shape(dns = "77.88.8.8", excluded = "192.168.7.0/24").toString()
        text shouldNotContain "77.88"
        text shouldNotContain "192.168"
        RoutePlan.Exclude(ranges("192.168.7.0/24")).toString() shouldNotContain "192.168"
        RoutePlan.Add(ranges("192.168.7.0/24")).toString() shouldNotContain "192.168"
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun planAdvertising(address: String) =
        DnsPlan(
            servers = listOf(DnsServerSpec(address)),
            hosts = emptyMap(),
            fakeDns = false,
            directMatch = null,
            proxyMatch = null,
        )

    private fun addRoutes(list: List<CidrRange>): List<CidrRange> =
        (routePlanFor(list, 32) as RoutePlan.Add).routes

    private fun bits(v6: Boolean) = if (v6) 128 else 32

    // A numeric literal: InetAddress performs no lookup for one. It does turn a v4-mapped IPv6 literal
    // into an Inet4Address, so that case is mapped back into ::ffff:0:0/96.
    private fun value(address: String): BigInteger {
        val raw = InetAddress.getByName(address).address
        val plain = BigInteger(1, raw)
        return if (isV6(address) && raw.size == 4) BigInteger.valueOf(0xffff).shiftLeft(32) + plain else plain
    }

    private fun isV6(address: String) = ':' in address

    private fun start(range: CidrRange) = value(range.address)

    private fun size(range: CidrRange) = BigInteger.ONE.shiftLeft(bits(range.isV6) - range.prefixLength)

    private fun end(range: CidrRange) = start(range) + size(range) - BigInteger.ONE

    private fun inBlock(
        range: CidrRange,
        address: String,
    ) = range.isV6 == isV6(address) && value(address) >= start(range) && value(address) <= end(range)

    private fun inAny(
        routes: List<CidrRange>,
        address: String,
    ) = routes.any { inBlock(it, address) }

    private fun overlaps(
        a: CidrRange,
        b: CidrRange,
    ) = a.isV6 == b.isV6 && start(a) <= end(b) && start(b) <= end(a)

    private fun covers(
        outer: CidrRange,
        inner: CidrRange,
    ) = outer.isV6 == inner.isV6 && start(outer) <= start(inner) && end(inner) <= end(outer)

    /**
     * Per family: the routes are pairwise disjoint, miss every excluded range, and together with the
     * excluded ranges (and the loopback / v4-mapped blocks the plan never routes) cover the whole family
     * exactly once.
     */
    private fun assertPartition(
        excluded: List<CidrRange>,
        routes: List<CidrRange>,
    ) {
        for (v6 in listOf(false, true)) {
            val family = routes.filter { it.isV6 == v6 }
            val ranges = excluded.filter { it.isV6 == v6 }
            val unroutable =
                if (ranges.isEmpty()) {
                    emptyList()
                } else if (v6) {
                    listOf(CidrRange("::1", 128, true), CidrRange("::ffff:0:0", 96, true))
                } else {
                    listOf(CidrRange("127.0.0.0", 8, false))
                }
            for (i in family.indices) {
                for (j in i + 1 until family.size) overlaps(family[i], family[j]) shouldBe false
            }
            for (route in family) {
                (ranges + unroutable).none { overlaps(route, it) } shouldBe true
            }
            val holes = (ranges + unroutable).distinct()
            val outermost = holes.filter { h -> holes.none { o -> o != h && covers(o, h) } }
            val covered = (family + outermost).fold(BigInteger.ZERO) { acc, r -> acc + size(r) }
            covered shouldBe BigInteger.ONE.shiftLeft(bits(v6))
        }
    }
}
