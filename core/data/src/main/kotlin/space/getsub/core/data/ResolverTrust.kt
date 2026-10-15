// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

/** M9-C spec §1.3: the per-subscription grant for the provider's DoH resolver, bound to its host. */
public enum class ResolverTrust {
    Unasked,
    Allowed,
    Denied,
    ;

    internal val stored: String get() = name.uppercase()
}

internal fun resolverTrustOf(stored: String?): ResolverTrust =
    ResolverTrust.entries.firstOrNull { it.stored == stored } ?: ResolverTrust.Unasked
