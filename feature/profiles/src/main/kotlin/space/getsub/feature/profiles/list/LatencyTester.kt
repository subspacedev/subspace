// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.list

import kotlinx.coroutines.flow.StateFlow
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.PingMode
import space.getsub.service.latency.LatencyRuns
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The measurement surface the Servers screen needs, behind one seam.
 *
 * Exists for the same reason [ProfileSource][space.getsub.feature.profiles.ProfileSource]
 * does: [LatencyRuns], behind it, cannot be built by hand in a `:feature:profiles` unit test
 * (its testable constructor is scoped to `:service`, and it needs a live binder). Screens talk
 * to this; [BoundLatencyTester] is the one place that touches the real collaborator.
 *
 * Nothing here runs unasked: the launch test moved to the fresh open, in Home's
 * LaunchAutomation (M10-C spec §3.2), so this seam no longer carries its gates.
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
}

@Singleton
internal class BoundLatencyTester
@Inject
constructor(
    private val runs: LatencyRuns,
) : LatencyTester {
    override val results: StateFlow<Map<Long, LatencyResult>> = runs.results
    override val testing: StateFlow<Set<Long>> = runs.testing

    // The run-id fence and the supersede-clears-testing rule live in LatencyRuns, shared
    // with Home (M10-C spec §3.1).
    override suspend fun test(
        profileIds: List<Long>,
        modes: Map<Long, PingMode>,
    ): Boolean = runs.start(profileIds, modes)

    override fun cancel() = runs.cancel()
}
