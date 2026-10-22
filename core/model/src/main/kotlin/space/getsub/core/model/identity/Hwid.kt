// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model.identity

import java.security.SecureRandom

public sealed interface HwidResult {
    public data class Valid(
        val value: String,
    ) : HwidResult {
        override fun toString(): String = "Valid(<redacted>)"
    }

    public data object Invalid : HwidResult
}

/** Per-subscription HWID rules (M10-B spec §3.2). The format is the panel's own: `HwidProvider.kt`. */
public object Hwid {
    private val FORMAT = Regex("^[a-zA-Z0-9=-]{10,64}$")
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val GENERATED_LENGTH = 32

    /** Trims [input], then checks it against the panel's format. */
    public fun validate(input: String): HwidResult {
        val trimmed = input.trim()
        return if (FORMAT.matches(trimmed)) HwidResult.Valid(trimmed) else HwidResult.Invalid
    }

    /** 32 characters from [A-Za-z0-9]; always passes [validate]. */
    public fun random(random: SecureRandom = SecureRandom()): String =
        buildString(GENERATED_LENGTH) {
            repeat(GENERATED_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
}
