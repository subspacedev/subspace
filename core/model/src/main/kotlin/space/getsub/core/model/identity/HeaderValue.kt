// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model.identity

/** Rules for the values sent in the four device headers (M10-B spec §3.5). */
public object HeaderValue {
    private const val MAX_LENGTH = 128
    private const val FIRST_PRINTABLE = 0x20
    private const val LAST_PRINTABLE = 0x7E

    private fun printable(c: Char): Boolean = c.code in FIRST_PRINTABLE..LAST_PRINTABLE

    /** The trimmed value, or null when it is empty, too long, or has a non-printable or non-ASCII character. */
    public fun validateLiteral(input: String): String? {
        val trimmed = input.trim()
        return trimmed.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH && it.all(::printable) }
    }

    /** Makes a device-derived value safe to send; null when nothing printable remains, so the header is omitted. */
    public fun sanitiseDevice(raw: String): String? =
        raw
            .filter(::printable)
            .trim()
            .take(MAX_LENGTH)
            .trim()
            .ifEmpty { null }
}
