// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.preresolve

import space.getsub.core.network.preresolve.IpLiteral
import space.getsub.core.parser.RedirectCheck
import space.getsub.core.parser.RedirectTargets

internal const val KEY_RESOLVE_ENABLE = "server-address-resolve-enable"
internal const val KEY_RESOLVE_DOMAIN = "server-address-resolve-dns-domain"
internal const val KEY_RESOLVE_IP = "server-address-resolve-dns-ip"
internal val PRERESOLVE_DIRECTIVE_KEYS = listOf(KEY_RESOLVE_ENABLE, KEY_RESOLVE_DOMAIN, KEY_RESOLVE_IP)

/** The provider's `server-address-resolve-*` directives after validation (M9-C spec §1.2). */
public sealed interface ProviderResolver {
    public data object Absent : ProviderResolver

    /** [key] names the directive that failed validation. */
    public data class Invalid(
        val key: String,
    ) : ProviderResolver

    /** [host] is lowercase, without a trailing dot, for display and for binding the grant. */
    public data class Valid(
        val dohUrl: String,
        val host: String,
        val bootstrapIp: String?,
    ) : ProviderResolver {
        // ARCHITECTURE.md §5.6: a provider's resolver is subscription content.
        override fun toString(): String =
            "Valid(dohUrl=<redacted>, host=<redacted>, bootstrapIp=${bootstrapIp != null})"
    }
}

@Suppress("ReturnCount") // Absent, then one Invalid per directive, then Valid.
public fun providerResolverOf(directives: Map<String, String?>): ProviderResolver {
    val enabled = directives[KEY_RESOLVE_ENABLE]?.trim()?.lowercase() in setOf("true", "1")
    if (!enabled) return ProviderResolver.Absent
    val url = directives[KEY_RESOLVE_DOMAIN] ?: return ProviderResolver.Invalid(KEY_RESOLVE_DOMAIN)
    val check = RedirectTargets.checkUrl(url.trim())
    if (check !is RedirectCheck.Ok) return ProviderResolver.Invalid(KEY_RESOLVE_DOMAIN)
    val ip = directives[KEY_RESOLVE_IP]?.trim()?.takeIf(String::isNotEmpty)
    if (ip != null && !IpLiteral.isLiteral(ip)) return ProviderResolver.Invalid(KEY_RESOLVE_IP)
    return ProviderResolver.Valid(check.url, check.host.removeSuffix("."), ip)
}
