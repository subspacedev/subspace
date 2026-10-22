// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.list

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import space.getsub.core.data.LatencyCache
import space.getsub.core.data.SettingsRepository
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.PingMode
import space.getsub.service.TunnelClient
import space.getsub.service.latency.LatencyRuns
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The measurement surface the Servers screen needs, behind one seam.
 *
 * Exists for the same reason [ProfileSource][space.getsub.feature.profiles.ProfileSource]
 * does: the things behind it — [LatencyCache], [SettingsRepository] and
 * [TunnelClient] — cannot be built by hand in a `:feature:profiles` unit test
 * ([SettingsRepository]'s constructor is `internal` to `:core:data`, and
 * [TunnelClient] needs a `Context` and a live binder). Screens talk to this;
 * [BoundLatencyTester] is the one place that touches the real collaborators.
 */
internal interface LatencyTester {
    /** Measured results by profile id. Absence means never measured, never zero. */
    val results: StateFlow<Map<Long, LatencyResult>>

    /** Profile ids with a measurement in flight. */
    val testing: StateFlow<Set<Long>>

    /**
     * Measures [profileIds], superseding any run already in flight.
     *
     * A single-server test is just a one-element list — there is deliberately no
     * separate path for it, so the row action and the group action cannot drift.
     *
     * **Implementations must release the rows a superseded run had marked.** That
     * run never reaches its own `onFinished` — it is fenced on the run id in
     * `:bg` — so anything it marked would otherwise show "…" for the rest of the
     * session. Reachable by tapping "Test all" and then a single row.
     *
     * @param modes per-profile overrides. `ping-type` is scoped to the
     *   subscription that delivered it (§A.1), so one run spanning two groups can
     *   need two modes; an absent entry means the user's global setting.
     */
    suspend fun test(
        profileIds: List<Long>,
        modes: Map<Long, PingMode> = emptyMap(),
    ): Boolean

    /**
     * Stops scheduling the current run.
     *
     * Not instantaneous: a measurement already inside libXray's blocking ping
     * finishes on its own, and its result is discarded rather than shown. Rows
     * still testing return to idle.
     */
    fun cancel()

    /** True the first time it is asked for [groupId] this session — see [LatencyCache.claimLaunchRun]. */
    fun claimLaunchRun(groupId: Long): Boolean

    /** Returns claims taken for a run that never started — see [LatencyCache.releaseLaunchRun]. */
    fun releaseLaunchRun(groupIds: Collection<Long>)

    /** The user's global ping-on-launch setting. */
    val pingOnLaunch: Flow<Boolean>

    /** Whether ping-on-launch may run while the active network is metered. */
    val pingOnLaunchMetered: Flow<Boolean>

    /**
     * Whether the active network is metered right now.
     *
     * On this seam rather than injected into the screen so `:feature:profiles`'
     * unit tests need no `Context` — the same reason the rest of this interface
     * exists.
     */
    fun isMetered(): Boolean

    /**
     * The tunnel's current state, so a launch run can stay out of the way of an
     * in-flight connect (§5.3).
     */
    fun connectionState(): ConnectionState
}

@Singleton
internal class BoundLatencyTester
@Inject
constructor(
    private val cache: LatencyCache,
    private val tunnel: TunnelClient,
    private val settings: SettingsRepository,
    private val runs: LatencyRuns,
    @ApplicationContext private val context: Context,
) : LatencyTester {
    override val results: StateFlow<Map<Long, LatencyResult>> = runs.results
    override val testing: StateFlow<Set<Long>> = runs.testing
    override val pingOnLaunch: Flow<Boolean> = settings.pingOnLaunch
    override val pingOnLaunchMetered: Flow<Boolean> = settings.pingOnLaunchMetered

    // The run-id fence and the supersede-clears-testing rule live in LatencyRuns, shared
    // with Home (M10-C spec §3.1).
    override suspend fun test(
        profileIds: List<Long>,
        modes: Map<Long, PingMode>,
    ): Boolean = runs.start(profileIds, modes)

    override fun cancel() = runs.cancel()

    override fun claimLaunchRun(groupId: Long): Boolean = cache.claimLaunchRun(groupId)

    override fun releaseLaunchRun(groupIds: Collection<Long>) = cache.releaseLaunchRun(groupIds)

    /**
     * Read at the moment a launch run is considered, not cached: the user may
     * have moved off Wi-Fi since the app started, and this is the gate that keeps
     * an unprompted forty-server run off their cellular data.
     */
    override fun isMetered(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    override fun connectionState(): ConnectionState = tunnel.state.value
}
