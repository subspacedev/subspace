// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

/**
 * Whether [ruleSet] has rules that cannot match while sniffing is off (M9-E spec §2, "warn but obey").
 *
 * Traffic reaches the SOCKS inbound addressed to an IP, so only sniffing recovers a domain. Every
 * entry in a [RuleBucket.sites] list is domain-matching — plain domains and the `domain:`, `full:`,
 * `keyword:`, `dotless:`, `regexp:`, `geosite:` and `ext:` forms ([RoutingEntries]) — so any one of
 * them warns. [RuleBucket.ips] entries (CIDRs, `geoip:`, IP `ext:`) match the address itself and never do.
 *
 * [sniffing] is the effective value. The warning stands even under FakeDNS, whose `fakedns`-only
 * block maps fake addresses back to domains but leaves real-IP connections unsniffed.
 */
public fun sniffingOffBreaksRules(
    sniffing: Boolean,
    ruleSet: RoutingRuleSet?,
): Boolean = !sniffing && ruleSet != null && ruleSet.buckets.values.any { it.sites.isNotEmpty() }
