// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.format

private const val REGIONAL_INDICATOR_A = 0x1F1E6
private const val REGIONAL_INDICATOR_Z = 0x1F1FF

/**
 * The first flag emoji in [text], or null. Server names carry their flag
 * anywhere ("Хельсинки 🇫🇮 XHTTP"), so this scans rather than reading only
 * the first characters. A flag is two regional indicators in a row
 * (M8.6 spec §2.8: rendered natively, no bundled emoji font).
 */
fun flagIn(text: String): String? {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val width = Character.charCount(cp)
        val next = i + width
        if (cp.isRegionalIndicator() && next < text.length) {
            val second = text.codePointAt(next)
            if (second.isRegionalIndicator()) return text.substring(i, next + Character.charCount(second))
        }
        i = next
    }
    return null
}

/**
 * [name] without the flag [flagIn] finds, for places that show that flag separately (a tile).
 * The whitespace the flag leaves behind is collapsed and trimmed. A name with no flag, or
 * made of nothing but a flag, comes back unchanged — an empty title helps nobody.
 */
fun nameWithoutFlag(name: String): String {
    val flag = flagIn(name) ?: return name
    val stripped = name.replaceFirst(flag, "").replace(Regex("\\s+"), " ").trim()
    return stripped.ifEmpty { name }
}

private fun Int.isRegionalIndicator() = this in REGIONAL_INDICATOR_A..REGIONAL_INDICATOR_Z
