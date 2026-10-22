// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import space.getsub.core.data.SettingsRepository
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.Profile
import space.getsub.core.model.TrafficSample
import space.getsub.service.TunnelClient
import space.getsub.service.latency.LatencyRuns
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapts the AIDL-backed [TunnelClient] to the narrow view this screen needs.
 *
 * The indirection exists so [HomeViewModel] can be reasoned about without a live
 * binder — and so the screen never touches the process boundary itself.
 */
@Singleton
internal class BoundTunnelConnection @Inject constructor(
    private val client: TunnelClient,
    private val runs: LatencyRuns,
    private val settings: SettingsRepository,
) : TunnelConnection {
    override val state: StateFlow<ConnectionState> get() = client.state

    override val latencies: StateFlow<Map<Long, LatencyResult>> get() = runs.results

    override val measuring: StateFlow<Set<Long>> get() = runs.testing

    override val traffic: StateFlow<TrafficSample?> get() = client.traffic

    override val pingOnLaunch: Flow<Boolean> get() = settings.pingOnLaunch

    override val perTagBreakdown: Flow<Boolean> get() = settings.perTagBreakdown

    override val lightweightMode: Flow<Boolean> get() = settings.lightweightMode

    override fun connect(
        profile: Profile,
        rowId: Long,
    ) = client.connect(profile, rowId)

    override fun disconnect() = client.disconnect()

    /**
     * Home follows the global setting: it measures the active profile, and has no group
     * context to resolve a provider's ping-type from. It shares [LatencyRuns] with Servers,
     * so starting here supersedes a Servers run and releases its rows (M10-C spec §3.1).
     */
    override suspend fun measure(profileId: Long) {
        @Suppress("IgnoredReturnValue")
        runs.start(listOf(profileId), emptyMap())
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object HomeModule {
    @Provides
    @Singleton
    fun tunnelConnection(impl: BoundTunnelConnection): TunnelConnection = impl

    @Provides
    @Singleton
    fun activeProfileSource(impl: BoundActiveProfileSource): ActiveProfileSource = impl

    @Provides
    @Singleton
    fun trafficPolicySource(impl: BoundTrafficPolicySource): TrafficPolicySource = impl
}
