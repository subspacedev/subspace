// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

private const val CAPTION_KEY = "?serverDescription="
private const val MAX_CAPTION_CHARS = 30

/** A link's display name and the optional provider caption that rode in on its fragment. */
internal data class NamedCaption(
    val name: String,
    val caption: String?,
)

/**
 * Splits Happ's `title?serverDescription=<base64>` fragment (research note 2026-10-22; Happ
 * app-management docs). A suffix that is not valid base64 text is not a caption, so the whole
 * string stays the name: a title may legitimately contain `?`.
 */
internal fun splitCaption(fragment: String): NamedCaption {
    val at = fragment.indexOf(CAPTION_KEY)
    if (at < 0) return NamedCaption(fragment, null)
    val decoded = decodeBase64Tolerant(fragment.substring(at + CAPTION_KEY.length))
    // A decode that is not clean text (a replacement character or a control character) is not a
    // caption: the whole string stays the name, like an undecodable suffix.
    val caption = decoded?.takeIf(::isCleanText)?.let(::clampCaption)
    return if (caption == null) NamedCaption(fragment, null) else NamedCaption(fragment.substring(0, at), caption)
}

private fun isCleanText(text: String): Boolean = text.none { it == '\uFFFD' || Character.isISOControl(it) }

/** A caption from a plain-text source (the VMess JSON field); trimmed and bounded like the link form. */
internal fun clampCaption(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val end = trimmed.offsetByCodePoints(0, minOf(MAX_CAPTION_CHARS, trimmed.codePointCount(0, trimmed.length)))
    return trimmed.substring(0, end)
}
