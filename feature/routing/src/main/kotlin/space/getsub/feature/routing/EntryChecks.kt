// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import space.getsub.core.model.BucketField
import space.getsub.core.model.EntryProblem
import space.getsub.core.model.RoutingEntries

/**
 * Why the design's inline checks refuse an entry. A closed vocabulary; no member carries the
 * entry (ARCHITECTURE.md §5.6).
 */
internal enum class EntryHint {
    SpacesOrCommas,
    MissingCategory,
    UseGeositeForSites,
    UseGeoipForIps,
    MalformedExt,
    NotADomain,
    NotAnIp,
}

/** The design's inline checks (Phone.dc.html problemWith), ported verbatim. Null = acceptable to these checks. */
// CyclomaticComplexMethod/ReturnCount: a line-for-line port of the design's function, kept in its
// shape so the two can be compared by eye; splitting it would hide which branch matches which line.
@Suppress("CyclomaticComplexMethod", "ReturnCount")
internal fun problemWith(
    raw: String,
    field: BucketField,
): EntryHint? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    if (t.any { it.isWhitespace() || it == ',' }) return EntryHint.SpacesOrCommas
    GEO.matchEntire(t)?.let { m ->
        val (kind, rest) = m.destructured
        if (rest.isEmpty()) return EntryHint.MissingCategory
        if (field == BucketField.SITES && !kind.equals("geosite", true)) return EntryHint.UseGeositeForSites
        if (field == BucketField.IPS && !kind.equals("geoip", true)) return EntryHint.UseGeoipForIps
        return null
    }
    if (t.startsWith("ext:", ignoreCase = true)) return if (EXT.matches(t)) null else EntryHint.MalformedExt
    return when (field) {
        BucketField.SITES -> if (SITE_PREFIXED.matches(t) || DOMAIN.matches(t)) null else EntryHint.NotADomain
        BucketField.IPS -> if (IP.matches(t)) null else EntryHint.NotAnIp
    }
}

private val GEO = Regex("^(geosite|geoip):(.*)$", RegexOption.IGNORE_CASE)
private val EXT = Regex("^ext:[^:]+\\.dat:[^:]+$", RegexOption.IGNORE_CASE)
private val SITE_PREFIXED = Regex("^(domain|full|keyword|regexp):.+$", RegexOption.IGNORE_CASE)
private val DOMAIN = Regex("^([a-z0-9-]+\\.)+[a-z]{2,}$", RegexOption.IGNORE_CASE)
private val IP =
    Regex("^(\\d{1,3}(\\.\\d{1,3}){3}(/\\d{1,2})?|[0-9a-f]*:[0-9a-f:]+(/\\d{1,3})?)$", RegexOption.IGNORE_CASE)

/**
 * The first reason an entry is refused (M8.6 Part 4 plan ruling R4): the design's check runs first
 * and its message wins; `:core:model`'s [EntryProblem] runs after it and catches what the design's
 * rules let through. An entry is acceptable only when this is null — both checks accept it.
 */
internal sealed interface EntryRejection {
    data class Hint(val hint: EntryHint) : EntryRejection

    data class Problem(val problem: EntryProblem) : EntryRejection
}

/** See [EntryRejection]. Null when [raw] is acceptable to both checks. */
internal fun rejectionOf(
    raw: String,
    field: BucketField,
): EntryRejection? =
    problemWith(raw, field)?.let(EntryRejection::Hint)
        ?: RoutingEntries.problemWith(raw, field)?.let(EntryRejection::Problem)
