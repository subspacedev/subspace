// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

/** M9-B spec §1.2: one grant per subscription covering `new-url`, `new-domain` and `fallback-url`. */
public enum class RedirectTrust {
    Unasked,
    Allowed,
    Denied,
    ;

    internal val stored: String get() = name.uppercase()
}

internal fun redirectTrustOf(stored: String?): RedirectTrust =
    RedirectTrust.entries.firstOrNull { it.stored == stored } ?: RedirectTrust.Unasked

/** Which address answered the last refresh (M9-B spec §3.3, plan ruling P6). */
public enum class FetchVia {
    Main,
    Fallback,
    FallbackFailed,
    ;

    internal val stored: String get() = name.uppercase()
}

internal fun fetchViaOf(stored: String?): FetchVia? = FetchVia.entries.firstOrNull { it.stored == stored }
