// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

class SniffingRuleWarningTest {
    private fun ruleSet(vararg b: Pair<RouteOutcome, RuleBucket>) = RoutingRuleSet(name = "r", buckets = mapOf(*b))

    private val domainRules = ruleSet(RouteOutcome.PROXY to RuleBucket(sites = listOf("geosite:youtube")))

    @Test
    fun sniffingOnNeverWarns() {
        sniffingRuleWarning(sniffing = true, ruleSet = domainRules, fakeDns = false) shouldBe SniffingRuleWarning.None
        sniffingRuleWarning(sniffing = true, ruleSet = domainRules, fakeDns = true) shouldBe SniffingRuleWarning.None
    }

    @Test
    fun noRuleSetNeverWarns() {
        sniffingRuleWarning(sniffing = false, ruleSet = null, fakeDns = false) shouldBe SniffingRuleWarning.None
    }

    @Test
    fun everyDomainMatchingEntryKindWarnsWhenOff() {
        listOf(
            "geosite:cn",
            "domain:example.com",
            "full:www.example.com",
            "keyword:example",
            "regexp:^ex.*\\.com$",
            "dotless:intranet",
            "ext:custom.dat:tag",
            "example.com",
        ).forEach { entry ->
            val rules = ruleSet(RouteOutcome.DIRECT to RuleBucket(sites = listOf(entry)))
            sniffingRuleWarning(sniffing = false, ruleSet = rules, fakeDns = false) shouldBe
                SniffingRuleWarning.DomainRulesStop
        }
    }

    @Test
    fun ipOnlySetsDoNotWarn() {
        val rules =
            ruleSet(
                RouteOutcome.DIRECT to RuleBucket(ips = listOf("geoip:private", "10.0.0.0/8")),
                RouteOutcome.BLOCK to RuleBucket(ips = listOf("ext:custom.dat:bad")),
            )

        sniffingRuleWarning(sniffing = false, ruleSet = rules, fakeDns = false) shouldBe SniffingRuleWarning.None
        sniffingRuleWarning(sniffing = false, ruleSet = rules, fakeDns = true) shouldBe SniffingRuleWarning.None
    }

    @Test
    fun anEmptySetDoesNotWarn() {
        sniffingRuleWarning(sniffing = false, ruleSet = ruleSet(), fakeDns = false) shouldBe SniffingRuleWarning.None
    }

    @Test
    fun oneDomainEntryAmongIpsWarns() {
        val rules =
            ruleSet(
                RouteOutcome.DIRECT to RuleBucket(ips = listOf("geoip:ru")),
                RouteOutcome.PROXY to RuleBucket(sites = listOf("domain:example.com")),
            )

        sniffingRuleWarning(sniffing = false, ruleSet = rules, fakeDns = false) shouldBe
            SniffingRuleWarning.DomainRulesStop
    }

    // Device run 2026-10-22, row 4b: the fakedns-only block maps fake addresses back to names, so
    // domain rules keep matching for lookups made through FakeDNS. Only the rest goes unmatched.
    @Test
    fun underFakeDnsDomainRulesMatchOnlyFakeDnsLookups() {
        sniffingRuleWarning(sniffing = false, ruleSet = domainRules, fakeDns = true) shouldBe
            SniffingRuleWarning.FakeDnsLookupsOnly
    }

    @Test
    fun fakeDnsCountsOnlyWhenTheBlockIsValidAndSaysTrue() {
        null.usesFakeDns() shouldBe false
        ProfileDns().usesFakeDns() shouldBe false
        ProfileDns(fakeDns = false).usesFakeDns() shouldBe false
        ProfileDns(fakeDns = true).usesFakeDns() shouldBe true
        ProfileDns.INVALID.usesFakeDns() shouldBe false
    }
}
