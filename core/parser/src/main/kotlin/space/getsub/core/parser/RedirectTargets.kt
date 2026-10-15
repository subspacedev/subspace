// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import java.net.URI

public enum class RedirectRejection { Malformed, NotHttps, HasUserInfo, IpLiteral, LocalHost, NotABareHost }

public sealed interface RedirectCheck {
    /** [url] is acted on; [host] is lowercase, for display and comparison. */
    public data class Ok(
        val url: String,
        val host: String,
    ) : RedirectCheck {
        override fun toString(): String = "Ok(url=<redacted>, host=<redacted>)"
    }

    public data class Rejected(
        val reason: RedirectRejection,
    ) : RedirectCheck
}

private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
private val BARE_HOST =
    Regex(
        """^(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$""",
        RegexOption.IGNORE_CASE,
    )

/**
 * M9-B spec §1.5: what a provider-sent redirect may point at. The URLs are attacker-controlled
 * in ARCHITECTURE.md §A.1's threat model, so anything that is not plainly a public HTTPS host is refused.
 */
public object RedirectTargets {
    public fun checkUrl(raw: String): RedirectCheck {
        val uri = javaUriOrNull(raw)
        val scheme = uri?.scheme
        // One trailing dot is the same host in DNS (`localhost.`), so it must not slip past the checks below.
        val host = uri?.host?.lowercase()?.removeSuffix(".")
        return when {
            uri == null || scheme == null -> RedirectCheck.Rejected(RedirectRejection.Malformed)
            !scheme.equals("https", ignoreCase = true) -> RedirectCheck.Rejected(RedirectRejection.NotHttps)
            host.isNullOrBlank() -> RedirectCheck.Rejected(unparsedHostRejection(uri))
            uri.rawUserInfo != null -> RedirectCheck.Rejected(RedirectRejection.HasUserInfo)
            host.isIpLiteral() -> RedirectCheck.Rejected(RedirectRejection.IpLiteral)
            host.isLocal() -> RedirectCheck.Rejected(RedirectRejection.LocalHost)
            else -> RedirectCheck.Ok(raw.trim(), host)
        }
    }

    public fun applyDomain(
        currentPlainUrl: String,
        domain: String,
    ): RedirectCheck {
        val bare = domain.trim()
        val current = javaUriOrNull(currentPlainUrl)
        // IPv4 first: digits-and-dots (and `0x` labels) also match BARE_HOST. Anything with ':' (a port, a scheme) is
        // NotABareHost here, not IpLiteral — new-domain has no IPv6 form worth distinguishing.
        return when {
            bare.removeSuffix(".").isIpv4Like() -> RedirectCheck.Rejected(RedirectRejection.IpLiteral)
            !BARE_HOST.matches(bare) -> RedirectCheck.Rejected(RedirectRejection.NotABareHost)
            current == null -> RedirectCheck.Rejected(RedirectRejection.Malformed)
            else -> {
                val port = if (current.port == -1) "" else ":${current.port}"
                val query = current.rawQuery?.let { "?$it" }.orEmpty()
                checkUrl("${current.scheme}://${bare.lowercase()}$port${current.rawPath.orEmpty()}$query")
            }
        }
    }
}

private fun javaUriOrNull(raw: String): URI? = runCatching { URI(raw.trim()) }.getOrNull()

/**
 * A host Java's URI could not parse (`127.1`, `0x7f.1`, `1.2.3.4.` leave `host` null) is still refused as
 * an IP literal when its authority looks like one, so the reason shown is the real one.
 */
private fun unparsedHostRejection(uri: URI): RedirectRejection {
    val candidate =
        uri.rawAuthority
            ?.substringAfterLast('@')
            ?.substringBefore(':')
            ?.lowercase()
            ?.removeSuffix(".")
    return if (candidate != null && candidate.isIpv4Like()) RedirectRejection.IpLiteral else RedirectRejection.Malformed
}

private fun String.isIpLiteral(): Boolean = isIpv4Like() || contains(':') || startsWith('[')

/**
 * Dotted quads, and the shorthand and numeric forms resolvers also accept as IPv4 (`127.1`,
 * `2130706433`, `0x7f.1`): a last label that is all digits or hex-prefixed is never a public TLD.
 */
private fun String.isIpv4Like(): Boolean {
    val last = substringAfterLast('.')
    return IPV4.matches(this) || (last.isNotEmpty() && last.all { it.isDigit() }) || last.startsWith("0x")
}

private fun String.isLocal(): Boolean = this == "localhost" || endsWith(".localhost") || endsWith(".local")
