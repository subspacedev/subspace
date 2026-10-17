// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.preresolve

import space.getsub.core.data.ProfileRepository
import space.getsub.core.data.ResolverTrust
import space.getsub.core.data.SettingsRepository
import space.getsub.core.data.SubscriptionRepository
import space.getsub.core.data.resolverTrustOf
import space.getsub.core.data.sync.FetchDecisions
import space.getsub.core.network.preresolve.OkHttpDohLookupFactory
import space.getsub.core.network.preresolve.ServerAddressResolver
import space.getsub.core.network.preresolve.SocketProtect
import space.getsub.core.network.preresolve.TcpConnectRacer
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M9-C spec §1.1 and §3.1 at connect: reads the setting, the row's subscription and its grant fresh every
 * time, then resolves. The resolver is built here, not in `:service`, because only `:core:data` may depend
 * on `:core:network` (ARCHITECTURE.md §4); `:service` hands in what only it has as lambdas.
 */
@Singleton
public class ServerPreResolution
@Inject
internal constructor(
    private val settings: SettingsRepository,
    private val subscriptions: SubscriptionRepository,
    private val profiles: ProfileRepository,
) {
    /**
     * Decide, match, resolve for one start. [configNamesHost] is `:core:xray`'s raw-config matcher, which
     * this module must not depend on; [protect] is `VpnService.protect`, so the DoH query and the TCP probes
     * bypass the VPN on a reconnect with the TUN held open (ARCHITECTURE.md §5.1). Never throws bar
     * cancellation, which propagates (ARCHITECTURE.md §10.4).
     */
    @Suppress("LongParameterList")
    public suspend fun run(
        rowId: Long,
        host: String,
        port: Int,
        rawJsonRunsAsWritten: String?,
        configNamesHost: (rawJson: String, host: String) -> Boolean,
        protect: (Socket) -> Boolean,
        udpServer: Boolean = false,
    ): PreResolveOutcome {
        val socketProtect = SocketProtect { socket -> protect(socket) }
        val resolver = ServerAddressResolver(OkHttpDohLookupFactory(socketProtect), TcpConnectRacer(socketProtect))
        return runPreResolution(
            rowId,
            host,
            port,
            rawJsonRunsAsWritten,
            configNamesHost,
            ::decide,
            udpServer,
        ) { h, p, c, race -> resolver.resolve(h, p, c, race) }
    }

    internal suspend fun decide(rowId: Long): PreResolveDecision {
        val user = settings.preResolveSettingsNow()
        val fragment = FetchDecisions.fetchFragment(settings.fragmentSettingsNow(), linkFragment = null)
        val groupId = profiles.profile(rowId)?.groupId
        val subscriptionId = groupId?.let { subscriptions.subscriptionIdForGroup(it) }
        val provider =
            subscriptionId?.let { id ->
                providerResolverOf(
                    PRERESOLVE_DIRECTIVE_KEYS.associateWith { subscriptions.effective(id, it, null).value },
                )
            } ?: ProviderResolver.Absent
        val row = subscriptionId?.let { subscriptions.subscriptionRow(it) }
        return PreResolvePolicy.decide(
            user,
            fragment,
            provider,
            row?.let { resolverTrustOf(it.resolverTrust) } ?: ResolverTrust.Unasked,
            row?.resolverTrustHost,
        )
    }
}
