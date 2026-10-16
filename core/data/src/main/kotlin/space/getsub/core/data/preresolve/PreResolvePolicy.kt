// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.preresolve

import space.getsub.core.data.ResolverTrust
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.PreResolveSettings
import space.getsub.core.network.preresolve.IpLiteral
import space.getsub.core.network.preresolve.PreResolveReason
import space.getsub.core.network.preresolve.ResolverConfig
import space.getsub.core.parser.RedirectCheck
import space.getsub.core.parser.RedirectTargets

public enum class PreResolveSource { User, Provider }

internal sealed interface PreResolveDecision {
    /** [source] names whose setting turned the step off; null when nobody asked for it (M9-C spec §4.3). */
    data class Off(
        val reason: PreResolveReason,
        val source: PreResolveSource? = null,
    ) : PreResolveDecision

    data class Run(
        val config: ResolverConfig,
        val source: PreResolveSource,
    ) : PreResolveDecision
}

/** What subscription details and the Servers card show about the provider's resolver (M9-C spec §4.2). */
public data class ResolverStatus(
    val provider: ProviderResolver,
    val trust: ResolverTrust,
)

/** M9-C spec §1.1: user switch, else an allowed and valid provider resolver, else off. */
public object PreResolvePolicy {
    @Suppress("ReturnCount") // User branch, provider branch, off.
    internal fun decide(
        user: PreResolveSettings,
        userFragment: FragmentSpec?,
        provider: ProviderResolver,
        trust: ResolverTrust,
        trustHost: String?,
    ): PreResolveDecision {
        if (user.enabled) {
            val url = user.dohUrl.trim()
            val bootstrap = user.bootstrapIp.trim()
            val valid = RedirectTargets.checkUrl(url) is RedirectCheck.Ok && IpLiteral.isLiteral(bootstrap)
            return if (valid) {
                PreResolveDecision.Run(ResolverConfig(url, bootstrap, userFragment), PreResolveSource.User)
            } else {
                PreResolveDecision.Off(PreResolveReason.InvalidUserResolver, PreResolveSource.User)
            }
        }
        if (provider is ProviderResolver.Valid && effectiveTrust(trust, trustHost, provider) == ResolverTrust.Allowed) {
            return PreResolveDecision.Run(
                ResolverConfig(provider.dohUrl, provider.bootstrapIp, userFragment),
                PreResolveSource.Provider,
            )
        }
        return PreResolveDecision.Off(PreResolveReason.Off)
    }

    /** Plan ruling R2: a grant answered for another DoH host counts as unasked. */
    public fun effectiveTrust(
        stored: ResolverTrust,
        trustHost: String?,
        provider: ProviderResolver,
    ): ResolverTrust =
        when {
            provider !is ProviderResolver.Valid -> stored
            stored == ResolverTrust.Unasked -> ResolverTrust.Unasked
            trustHost?.lowercase()?.removeSuffix(".") == provider.host -> stored
            else -> ResolverTrust.Unasked
        }
}
