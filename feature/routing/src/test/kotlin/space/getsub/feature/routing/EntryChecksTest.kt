// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.BucketField
import space.getsub.core.model.BucketField.IPS
import space.getsub.core.model.BucketField.SITES
import space.getsub.core.model.EntryProblem
import space.getsub.core.model.RoutingEntries

/**
 * The design's inline checks ([problemWith], M8.6 spec §4.6) and how they combine with
 * `:core:model`'s [RoutingEntries.problemWith] (M8.6 Part 4 plan ruling R4): the design's check
 * runs first, the existing one after it, and an entry is accepted only when both accept it.
 *
 * The example inputs come from the design's own placeholder hints ("example.com or geosite:…",
 * "10.0.0.0/8 or geoip:…") and sample rule sets.
 */
class EntryChecksTest {
    // One test per design message.

    @Test
    fun `spaces or commas are refused in either field`() {
        problemWith("example.com, google.com", SITES) shouldBe EntryHint.SpacesOrCommas
        problemWith("example.com google.com", SITES) shouldBe EntryHint.SpacesOrCommas
        problemWith("10.0.0.0/8,192.168.0.0/16", IPS) shouldBe EntryHint.SpacesOrCommas
    }

    @Test
    fun `a geo prefix with nothing after the colon asks for a category`() {
        problemWith("geosite:", SITES) shouldBe EntryHint.MissingCategory
        problemWith("geoip:", IPS) shouldBe EntryHint.MissingCategory
    }

    @Test
    fun `geoip in a sites field asks for geosite`() {
        problemWith("geoip:private", SITES) shouldBe EntryHint.UseGeositeForSites
    }

    @Test
    fun `geosite in an ips field asks for geoip`() {
        problemWith("geosite:google", IPS) shouldBe EntryHint.UseGeoipForIps
    }

    @Test
    fun `a malformed ext reference asks for the file and category form`() {
        problemWith("ext:file.dat", SITES) shouldBe EntryHint.MalformedExt
        problemWith("ext:file:category", IPS) shouldBe EntryHint.MalformedExt
    }

    @Test
    fun `a site that is not a domain is refused`() {
        problemWith("example", SITES) shouldBe EntryHint.NotADomain
    }

    @Test
    fun `an ip that is not an address or range is refused`() {
        problemWith("example.com", IPS) shouldBe EntryHint.NotAnIp
    }

    @Test
    fun `the design's own examples are accepted`() {
        val sites =
            listOf(
                "example.com",
                "geosite:google",
                "geosite:category-ads-all",
                "full:api.openai.com",
                "domain:corp.example",
            )
        sites.forEach { problemWith(it, SITES).shouldBeNull() }
        val ips = listOf("10.0.0.0/8", "192.168.0.0/16", "geoip:private", "fc00::/7")
        ips.forEach { problemWith(it, IPS).shouldBeNull() }
        problemWith("ext:file.dat:category", SITES).shouldBeNull()
    }

    @Test
    fun `blank is not a design problem`() {
        problemWith("", SITES).shouldBeNull()
        problemWith("   ", IPS).shouldBeNull()
    }

    // R4 / review focus 3: entries only the existing check rejects are rejected.

    @Test
    fun `entries the design accepts but EntryProblem rejects are rejected`() {
        val cases =
            listOf(
                Triple("regexp:^.*\\.google\\.com$", SITES, EntryProblem.IllegalCharacter),
                Triple("GEOSITE:google", SITES, EntryProblem.MalformedDomain),
                Triple("keyword:a/b", SITES, EntryProblem.MalformedDomain),
                Triple("ext:-x.dat:cn", SITES, EntryProblem.MalformedExtReference),
                Triple("999.1.1.1", IPS, EntryProblem.MalformedAddress),
                Triple("10.0.0.0/33", IPS, EntryProblem.MalformedAddress),
            )
        cases.forEach { (entry, field, problem) ->
            problemWith(entry, field).shouldBeNull()
            rejectionOf(entry, field) shouldBe EntryRejection.Problem(problem)
        }
    }

    // R4 / review focus 3, the reverse: entries only the design's check rejects are rejected.
    // After ruling R4a this is only what `domain:` exists to cover: single-label and IDN names.

    @Test
    fun `entries EntryProblem accepts but the design rejects are rejected`() {
        val cases =
            listOf(
                Triple("localhost", SITES, EntryHint.NotADomain),
                Triple("bücher.de", SITES, EntryHint.NotADomain),
                Triple("!localhost", SITES, EntryHint.NotADomain),
            )
        cases.forEach { (entry, field, hint) ->
            RoutingEntries.problemWith(entry, field).shouldBeNull()
            rejectionOf(entry, field) shouldBe EntryRejection.Hint(hint)
        }
    }

    // M8.6 Part 4 ruling R4a: the design's check never rejects Xray syntax RoutingEntries
    // supports — `!` negation, the ext-site:/ext-domain:/ext-ip: variants and dotless:. Whether
    // they are usable is then EntryProblem's call alone.

    @Test
    fun `xray syntax the existing validator supports passes the design's check`() {
        val cases =
            listOf(
                "!geoip:cn" to IPS,
                "!10.0.0.0/8" to IPS,
                "ext-ip:x.dat:cat" to IPS,
                "ext-site:x.dat:cat" to SITES,
                "ext-domain:x.dat:cat" to SITES,
                "dotless:intranet" to SITES,
            )
        cases.forEach { (entry, field) ->
            problemWith(entry, field).shouldBeNull()
            rejectionOf(entry, field) shouldBe RoutingEntries.problemWith(entry, field)?.let(EntryRejection::Problem)
            rejectionOf(entry, field).shouldBeNull()
        }
    }

    @Test
    fun `EntryProblem still decides the xray syntax the design now lets through`() {
        // The design's shape check passes these; the existing filename grammar does not.
        rejectionOf("ext-ip:-x.dat:cat", IPS) shouldBe EntryRejection.Problem(EntryProblem.MalformedExtReference)
        rejectionOf("ext-site:-x.dat:cat", SITES) shouldBe EntryRejection.Problem(EntryProblem.MalformedExtReference)
        rejectionOf("!999.1.1.1", IPS) shouldBe EntryRejection.Problem(EntryProblem.MalformedAddress)
    }

    @Test
    fun `malformed xray variants are still rejected`() {
        problemWith("ext-ip:nodat", IPS) shouldBe EntryHint.MalformedExt
        problemWith("ext-site:x.dat", SITES) shouldBe EntryHint.MalformedExt
        problemWith("ext-domain:x.dat:", SITES) shouldBe EntryHint.MalformedExt
        problemWith("!", IPS) shouldBe EntryHint.NotAnIp
        problemWith("!", SITES) shouldBe EntryHint.NotADomain
        problemWith("!   ", IPS) shouldBe EntryHint.NotAnIp
        problemWith("! 10.0.0.0/8", IPS) shouldBe EntryHint.SpacesOrCommas
        listOf("!" to IPS, "!" to SITES, "!   " to IPS, "ext-ip:nodat" to IPS).forEach { (entry, field) ->
            rejectionOf(entry, field).shouldNotBeNull()
        }
    }

    @Test
    fun `when both checks reject, the design's message is the one shown`() {
        RoutingEntries.problemWith("geoip:cn", SITES).shouldNotBeNull()
        rejectionOf("geoip:cn", SITES) shouldBe EntryRejection.Hint(EntryHint.UseGeositeForSites)
        RoutingEntries.problemWith("geosite:google", IPS).shouldNotBeNull()
        rejectionOf("geosite:google", IPS) shouldBe EntryRejection.Hint(EntryHint.UseGeoipForIps)
    }

    @Test
    fun `an entry both checks accept has no rejection`() {
        mapOf<String, BucketField>(
            "example.com" to SITES,
            "geosite:google" to SITES,
            "full:api.openai.com" to SITES,
            "ext:file.dat:category" to SITES,
            "10.0.0.0/8" to IPS,
            "geoip:private" to IPS,
            "fc00::/7" to IPS,
        ).forEach { (entry, field) -> rejectionOf(entry, field).shouldBeNull() }
    }

    @Test
    fun `a blank entry is rejected by the existing check`() {
        rejectionOf("   ", SITES) shouldBe EntryRejection.Problem(EntryProblem.Blank)
    }
}
