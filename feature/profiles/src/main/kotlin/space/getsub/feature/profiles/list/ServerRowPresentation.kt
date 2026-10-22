// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.list

import space.getsub.core.model.LatencyOutcome
import space.getsub.core.ui.component.NoteTone
import space.getsub.core.ui.format.flagIn
import space.getsub.core.ui.format.nameWithoutFlag
import java.text.BreakIterator
import java.util.Locale

/** The one note a row shows, in M8.6 Part 3 R3's priority order. */
internal enum class RowNote { NotConnectable, Dropped, Compatibility }

/** The most actionable reason first (M8.6 Part 3 R3); null when the row has none. */
internal fun ServerRow.note(): RowNote? =
    when {
        !connectable -> RowNote.NotConnectable
        droppedFromSubscriptionAt != null -> RowNote.Dropped
        compatibilityMode -> RowNote.Compatibility
        else -> null
    }

/** Compatibility mode is a fact, not a fault; the other two stop the row working. */
internal fun RowNote.tone(): NoteTone = if (this == RowNote.Compatibility) NoteTone.Info else NoteTone.Error

private const val MAX_RANK = 3

/**
 * Rank 1..3 by ascending delay among rows with an `OK` outcome; ties go by list order.
 * Called per group (M8.6 Part 3 R4). Reads `delayMillis` only for `OK` (M8.5 spec §10.1).
 */
internal fun List<ServerRow>.ranks(): Map<Long, Int> =
    filter { it.latency?.outcome == LatencyOutcome.OK }
        .sortedBy { it.latency?.delayMillis } // stable: ties keep list order
        .take(MAX_RANK)
        .mapIndexed { index, row -> row.id to index + 1 }
        .toMap()

/** What a row's latency pill says; the screen maps each to a string resource. */
internal sealed interface PillText {
    data object Testing : PillText

    data object None : PillText

    data class Ms(val ms: Int) : PillText

    data object Failed : PillText

    data object Unsupported : PillText

    data object ForeignVpn : PillText

    /** M9-D1 spec §2: the certificate probe named the failure; the fix is the server's pin. */
    data object CertUntrusted : PillText
}

/** Mirrors Home's latency tile. `delayMillis` is read on `OK` alone (M8.5 spec §10.1). */
internal fun ServerRow.pill(): PillText {
    val result = latency
    return when {
        isTesting -> PillText.Testing
        result == null -> PillText.None
        result.outcome == LatencyOutcome.OK -> PillText.Ms(result.delayMillis)
        result.outcome == LatencyOutcome.UNSUPPORTED -> PillText.Unsupported
        result.outcome == LatencyOutcome.FOREIGN_VPN -> PillText.ForeignVpn
        result.outcome == LatencyOutcome.CERT_UNTRUSTED -> PillText.CertUntrusted
        else -> PillText.Failed
    }
}

/** The row's flag emoji wherever it sits in the name, else its initials. */
internal fun ServerRow.leadingMark(): String = flagIn(name) ?: name.codeTileInitials()

/** The name shown beside [leadingMark]: without the flag that tile already carries. */
internal fun ServerRow.displayName(): String = nameWithoutFlag(name)

/** The provider's caption, or null when it is absent or blank (M10-B spec §2). */
internal fun ServerRow.displayCaption(): String? = caption?.trim()?.takeIf { it.isNotEmpty() }

/** True when the group's sort came from its provider (S6, M8.6 Part 3 R5). */
internal fun ServersGroup.showsProviderOrder(): Boolean = sortFromProvider

/**
 * The code tile's label: the first grapheme cluster of up to two words in
 * [this] that actually start with a letter or digit — e.g. "Frankfurt" ->
 * "F", "US East" -> "UE".
 *
 * Device-fixes finding: real profile names lead with an emoji (`"🚀 Авто |
 * Лучший сервер 🇪🇺"`) or carry a flag mid-string (`"Хельсинки 🇫🇮 XHTTP"`).
 * A `String` is UTF-16, so the rocket is a surrogate pair and each half of a
 * flag is a regional-indicator code point that is *itself* a surrogate pair
 * — taking `Char`s (the previous `take(1)`/`take(2)`) or even whole code
 * points off the front could still land mid-pair or mid-flag, producing an
 * unpaired surrogate that renders as `<27>`.
 *
 * The fix is two-layered:
 *  - Skip whole words that carry no letter/digit at all (an emoji, a flag,
 *    a bare `|` separator) rather than truncating into them. A letter is
 *    what makes a tile useful for scanning a list; an emoji is not, and a
 *    name starting with one should not make its tile any less legible than
 *    a name that doesn't.
 *  - Read a qualifying word's *first grapheme cluster* via [BreakIterator],
 *    not its first `Char` or even its first code point, since a combining
 *    mark can still trail a single-code-point base letter.
 *
 * A name with no letter/digit anywhere — blank, or entirely emoji/symbols —
 * falls back to `"?"`, the same sentinel the empty-name case already used.
 * Pure function of [this]: the same name always produces the same tile.
 */
internal fun String.codeTileInitials(): String {
    val letteredWords =
        trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() && Character.isLetterOrDigit(it.codePointAt(0)) }
    if (letteredWords.isEmpty()) return "?"
    return letteredWords.take(2).joinToString("") { it.firstGraphemeCluster().uppercase(Locale.ROOT) }
}

/** The substring up to [this]'s first grapheme-cluster boundary, per [BreakIterator]. */
private fun String.firstGraphemeCluster(): String {
    val boundary = BreakIterator.getCharacterInstance(Locale.ROOT)
    boundary.setText(this)
    val end = boundary.next()
    return if (end == BreakIterator.DONE) this else substring(0, end)
}
