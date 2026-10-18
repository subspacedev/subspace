// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.math.BigInteger

class ExcludedRoutesTest {
    private fun v4(text: String) = (ExcludedRoutes.parse(text) as TuningChecked.Ok).value.single()

    private fun ranges(text: String) = (ExcludedRoutes.parse(text) as TuningChecked.Ok).value

    @Test
    fun parseNormalises() {
        ExcludedRoutes.parse("10.0.0.0/8, 192.168.1.5/24\n2001:DB8::/32") shouldBe
            TuningChecked.Ok(
                listOf(
                    CidrRange("10.0.0.0", 8, false),
                    CidrRange("192.168.1.0", 24, false),
                    CidrRange("2001:db8", 32, true).copy(address = "2001:db8::"),
                ),
            )
    }

    @Test
    fun parseBareIpsAndSeparators() {
        ranges("1.2.3.4 8.8.8.8,2001:db8::1\n\n") shouldBe
            listOf(
                CidrRange("1.2.3.4", 32, false),
                CidrRange("8.8.8.8", 32, false),
                CidrRange("2001:db8::1", 128, true),
            )
        v4("0:0:0:0:0:0:0:1").address shouldBe "::1"
        v4("2001:db8:0:0:1:0:0:1/128").address shouldBe "2001:db8::1:0:0:1"
    }

    @Test
    fun parseRejects() {
        val bad = TuningChecked.Invalid(TuningProblem.BadValue("exclude-routes"))
        ExcludedRoutes.parse("10.0.0.0/33") shouldBe bad
        ExcludedRoutes.parse("2001:db8::/129") shouldBe bad
        ExcludedRoutes.parse("10.0.0.0/") shouldBe bad
        ExcludedRoutes.parse("10.0.0/8") shouldBe bad
        ExcludedRoutes.parse("example.com") shouldBe bad
        ExcludedRoutes.parse("10.0.0.0/8 nonsense") shouldBe bad
    }

    @Test
    fun parseEmpty() {
        ExcludedRoutes.parse("") shouldBe TuningChecked.Ok(emptyList<CidrRange>())
        ExcludedRoutes.parse(" \n , ") shouldBe TuningChecked.Ok(emptyList<CidrRange>())
    }

    private fun problems(
        text: String,
        vararg dns: String,
    ) = ExcludedRoutes.validate(ranges(text), dns.toList())

    // Controller ruling: containing the TUN's own addresses is not "too wide"; nothing dials 10.7.0.x.
    @Test
    fun rangesCoveringTheTunAddressesAreValid() {
        problems("10.0.0.0/8 10.7.0.0/16") shouldBe emptyList()
        problems("fd00:1:2:3::/64") shouldBe emptyList()
        problems("0.0.0.0/1 ::/1") shouldBe emptyList()
    }

    @Test
    fun validateFlagsWideAndDns() {
        problems("0.0.0.0/0") shouldBe listOf(TuningProblem.RangeTooWide(0))
        problems("::/0") shouldBe listOf(TuningProblem.RangeTooWide(0))
        problems("192.168.0.0/16 ::/0") shouldBe listOf(TuningProblem.RangeTooWide(1))
        problems("1.1.1.0/24", "1.1.1.1") shouldBe listOf(TuningProblem.RangeCoversDns(0))
        problems("192.168.0.0/16", "1.1.1.1", "not-an-ip", "2606:4700::1111") shouldBe emptyList()
        problems("2606:4700::/32", "2606:4700::1111") shouldBe listOf(TuningProblem.RangeCoversDns(0))
    }

    @Test
    fun fingerprintIgnoresOrderButNotContent() {
        ExcludedRoutes.fingerprint(ranges("10.0.0.0/8 192.168.0.0/16")) shouldBe
            ExcludedRoutes.fingerprint(ranges("192.168.0.0/16 10.0.0.0/8"))
        val a = ExcludedRoutes.fingerprint(ranges("10.0.0.0/8"))
        val b = ExcludedRoutes.fingerprint(ranges("10.0.0.0/9"))
        (a == b) shouldBe false
        ExcludedRoutes.fingerprint(ranges("10.0.0.0/8")).length shouldBe 64
    }

    private fun value(address: String): BigInteger =
        address.split('.').fold(BigInteger.ZERO) { a, o ->
            a.shiftLeft(8) +
                BigInteger.valueOf(o.toLong())
        }

    private fun inAny(
        routes: List<CidrRange>,
        address: String,
    ): Boolean =
        routes.any {
            val start = value(it.address)
            val size = BigInteger.ONE.shiftLeft(32 - it.prefixLength)
            value(address) >= start && value(address) < start + size
        }

    @Test
    fun complementOfTenSlashEight() {
        val routes = ExcludedRoutes.complement(ranges("10.0.0.0/8"), v6 = false)
        inAny(routes, "9.255.255.255") shouldBe true
        inAny(routes, "10.0.0.1") shouldBe false
        inAny(routes, "11.0.0.0") shouldBe true
        routes.fold(BigInteger.ZERO) { a, r -> a + BigInteger.ONE.shiftLeft(32 - r.prefixLength) } +
            BigInteger.ONE.shiftLeft(24) shouldBe BigInteger.ONE.shiftLeft(32)
        routes.size shouldBe 8
    }

    @Test
    fun complementEdges() {
        ExcludedRoutes.complement(emptyList(), v6 = false) shouldBe listOf(CidrRange("0.0.0.0", 0, false))
        ExcludedRoutes.complement(emptyList(), v6 = true) shouldBe listOf(CidrRange("::", 0, true))
        ExcludedRoutes.complement(ranges("0.0.0.0/0"), v6 = false) shouldBe emptyList()
        // A v6 exclusion leaves the v4 family whole.
        ExcludedRoutes.complement(ranges("2001:db8::/32"), v6 = false) shouldBe listOf(CidrRange("0.0.0.0", 0, false))
        ExcludedRoutes.complement(ranges("2001:db8::/32"), v6 = true).size shouldBe 32
    }

    @Test
    fun coversIsContainmentWithinOneFamily() {
        ExcludedRoutes.covers(v4("10.0.0.0/8"), v4("10.1.0.0/16")) shouldBe true
        ExcludedRoutes.covers(v4("10.0.0.0/8"), v4("10.0.0.0/8")) shouldBe true
        ExcludedRoutes.covers(v4("10.1.0.0/16"), v4("10.0.0.0/8")) shouldBe false
        ExcludedRoutes.covers(v4("10.0.0.0/8"), v4("11.0.0.0/8")) shouldBe false
        ExcludedRoutes.covers(v4("127.0.0.0/8"), v4("127.255.255.255")) shouldBe true
        ExcludedRoutes.covers(v4("::ffff:0:0/96"), v4("::ffff:10.0.0.0/104")) shouldBe true
        ExcludedRoutes.covers(v4("::ffff:0:0/96"), v4("::fffe:0:0/96")) shouldBe false
        // Families never cover each other, even where the numbers line up.
        ExcludedRoutes.covers(v4("::/96"), v4("0.0.0.1")) shouldBe false
        ExcludedRoutes.covers(v4("0.0.0.0/8"), v4("::1")) shouldBe false
    }
}
