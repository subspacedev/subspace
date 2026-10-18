// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

class SniffingWarningTest {
    private fun ruleSet(vararg b: Pair<RouteOutcome, RuleBucket>) = RoutingRuleSet(name = "r", buckets = mapOf(*b))

    @Test
    fun sniffingOnNeverWarns() {
        val rules = ruleSet(RouteOutcome.PROXY to RuleBucket(sites = listOf("geosite:youtube")))

        sniffingOffBreaksRules(sniffing = true, ruleSet = rules) shouldBe false
    }

    @Test
    fun noRuleSetNeverWarns() {
        sniffingOffBreaksRules(sniffing = false, ruleSet = null) shouldBe false
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
            sniffingOffBreaksRules(sniffing = false, ruleSet = rules) shouldBe true
        }
    }

    @Test
    fun ipOnlySetsDoNotWarn() {
        val rules =
            ruleSet(
                RouteOutcome.DIRECT to RuleBucket(ips = listOf("geoip:private", "10.0.0.0/8")),
                RouteOutcome.BLOCK to RuleBucket(ips = listOf("ext:custom.dat:bad")),
            )

        sniffingOffBreaksRules(sniffing = false, ruleSet = rules) shouldBe false
    }

    @Test
    fun anEmptySetDoesNotWarn() {
        sniffingOffBreaksRules(sniffing = false, ruleSet = ruleSet()) shouldBe false
    }

    @Test
    fun oneDomainEntryAmongIpsWarns() {
        val rules =
            ruleSet(
                RouteOutcome.DIRECT to RuleBucket(ips = listOf("geoip:ru")),
                RouteOutcome.PROXY to RuleBucket(sites = listOf("domain:example.com")),
            )

        sniffingOffBreaksRules(sniffing = false, ruleSet = rules) shouldBe true
    }
}
