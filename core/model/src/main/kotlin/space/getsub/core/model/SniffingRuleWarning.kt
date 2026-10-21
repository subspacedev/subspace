// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

/** What sniffing off does to a rule set's domain rules (M9-E spec §2, "warn but obey"). */
public enum class SniffingRuleWarning {
    /** Sniffing is on, or the set has no domain rules: nothing to say. */
    None,

    /** No domain is recovered at all, so the set's domain rules match nothing. */
    DomainRulesStop,

    /**
     * The plan uses FakeDNS: its `fakedns`-only block maps fake addresses back to names, so domain
     * rules still match lookups made through it. Connections to a real IP (an app with its own DNS,
     * or one that dials an address) go unmatched. Device run 2026-10-22, row 4b.
     */
    FakeDnsLookupsOnly,
}

/**
 * The warning for [ruleSet] with sniffing at [sniffing] (the effective value) and FakeDNS at
 * [fakeDns] ([usesFakeDns] on the profile DNS block the tunnel would plan from).
 *
 * Traffic reaches the SOCKS inbound addressed to an IP, so only sniffing recovers a domain. Every
 * entry in a [RuleBucket.sites] list is domain-matching — plain domains and the `domain:`, `full:`,
 * `keyword:`, `dotless:`, `regexp:`, `geosite:` and `ext:` forms ([RoutingEntries]) — so any one of
 * them warns. [RuleBucket.ips] entries (CIDRs, `geoip:`, IP `ext:`) match the address itself and never do.
 */
public fun sniffingRuleWarning(
    sniffing: Boolean,
    ruleSet: RoutingRuleSet?,
    fakeDns: Boolean,
): SniffingRuleWarning =
    when {
        sniffing || ruleSet == null || ruleSet.buckets.values.none { it.sites.isNotEmpty() } -> SniffingRuleWarning.None
        fakeDns -> SniffingRuleWarning.FakeDnsLookupsOnly
        else -> SniffingRuleWarning.DomainRulesStop
    }

/**
 * Whether this block turns FakeDNS on: the same test `DnsPlanner.plan` applies (an invalid block is
 * ignored; null means the profile did not say, which is off).
 */
public fun ProfileDns?.usesFakeDns(): Boolean = this != null && !isInvalid && fakeDns == true
