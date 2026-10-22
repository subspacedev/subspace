// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service.latency

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import space.getsub.core.data.LatencyCache
import space.getsub.core.data.SettingsRepository
import space.getsub.core.model.LatencyOptions
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.LatencyTarget
import space.getsub.core.model.PingMode
import space.getsub.service.TunnelClient
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two calls [LatencyRuns] makes on the tunnel, behind a seam.
 *
 * [TunnelClient] has a private constructor and needs a `Context` and a live binder, so a JVM
 * test cannot build one. This is the narrowest cut that makes the fence logic testable.
 */
internal interface LatencyDispatcher {
    /** See [TunnelClient.startLatencyRun]: false when the run never reached `:bg`. */
    fun start(
        runId: Long,
        targets: List<LatencyTarget>,
        options: LatencyOptions,
        onResult: (Long, LatencyResult) -> Unit,
        onFinished: () -> Unit,
    ): Boolean

    fun cancel(runId: Long)
}

private class TunnelLatencyDispatcher(private val tunnel: TunnelClient) : LatencyDispatcher {
    override fun start(
        runId: Long,
        targets: List<LatencyTarget>,
        options: LatencyOptions,
        onResult: (Long, LatencyResult) -> Unit,
        onFinished: () -> Unit,
    ): Boolean = tunnel.startLatencyRun(runId, targets, options, onResult, onFinished)

    override fun cancel(runId: Long) = tunnel.cancelLatencyRun(runId)
}

/**
 * The one place a latency run is started, for Home, Servers and auto-connect (M10-C spec §3.1).
 *
 * Before this, Home and Servers each kept their own run-id scheme over the same single-run
 * `LatencyRunner` and the same [LatencyCache]; each had to remember to release the other's
 * rows when it superseded them, and the fix had once been applied to only one of the two.
 * One counter and one supersede path remove that class of drift.
 */
@Singleton
public class LatencyRuns
internal constructor(
    private val dispatcher: LatencyDispatcher,
    private val cache: LatencyCache,
    private val options: suspend () -> LatencyOptions,
) {
    @Inject
    public constructor(
        tunnel: TunnelClient,
        cache: LatencyCache,
        settings: SettingsRepository,
    ) : this(
        TunnelLatencyDispatcher(tunnel),
        cache,
        {
            // Resolved here rather than in :bg, so the service never has to reach
            // into settings to interpret a run.
            LatencyOptions(
                mode = settings.pingMode.first(),
                timeoutSeconds = settings.pingTimeoutSeconds.first(),
                checkUrl = settings.pingCheckUrl.first(),
            )
        },
    )

    /** Measured results by profile id. Absence means never measured, never zero. */
    public val results: StateFlow<Map<Long, LatencyResult>> = cache.results

    /** Profile ids with a measurement in flight. */
    public val testing: StateFlow<Set<Long>> = cache.testing

    /**
     * Monotonic, and the fence both sides of the binder check. Cancellation
     * cannot interrupt an in-flight native ping, so a superseded run's results
     * still arrive; without a fresh id per run they would land on rows the new
     * run is retesting.
     */
    private val runId = AtomicLong(0)

    /**
     * The id of the latest run started, from any caller. Read right after a caller's own [start]
     * and again later, it says whether another start has superseded that run since: auto-connect
     * treats one as the user taking over (M10-C spec §3.1). [cancel] does not move it.
     */
    public val currentRun: Long get() = runId.get()

    /**
     * Measures [profileIds], superseding any run already in flight.
     *
     * A single-server test is a one-element list: there is deliberately no separate path.
     *
     * @param modes per-profile overrides. `ping-type` is scoped to the subscription that
     *   delivered it (ARCHITECTURE.md §A.1), so one run spanning two groups can need two
     *   modes; an absent entry means the user's global setting.
     * @param onResult called with each result of *this* run, right after it is cached. [results]
     *   also holds earlier runs' values, so a caller that must decide on this run alone (the
     *   fastest pick, M10-C spec §3.1) collects here. Called on a binder thread.
     * @return false when nothing was started (empty list, or nothing bound to the tunnel).
     */
    public suspend fun start(
        profileIds: List<Long>,
        modes: Map<Long, PingMode>,
        onResult: ((Long, LatencyResult) -> Unit)? = null,
    ): Boolean {
        if (profileIds.isEmpty()) return false
        // Resolved first: it suspends (Room reads), and everything after it must not. Two
        // overlapping starts that interleaved around a suspension point could both clear, then
        // both mark and dispatch; the first is then superseded in :bg, its onFinished is fenced
        // away, and its rows stay on "…" for the session.
        val resolved = options()
        // From here to the dispatch there is no suspension point, so a start is atomic with
        // respect to every other start on this dispatcher.
        // Starting a run supersedes any run in flight, and a superseded run never
        // reaches its own onFinished — that is fenced on the run id in :bg.
        // Without this, the rows it had marked would sit on "…" for the rest of
        // the session. Clearing here is what makes supersession safe.
        cache.finish(cache.testing.value)
        val id = runId.incrementAndGet()
        cache.markTesting(profileIds)
        return dispatcher.start(
            runId = id,
            // Per profile: see [modes].
            targets = profileIds.map { profileId -> LatencyTarget(profileId, modes[profileId] ?: resolved.mode) },
            options = resolved,
            onResult = { profileId, result ->
                cache.put(profileId, result)
                onResult?.invoke(profileId, result)
            },
            // Clears the in-flight flag without writing results for rows that
            // never produced one — a measurement that did not happen must not be
            // recorded as one.
            onFinished = { cache.finish(profileIds) },
        )
    }

    /**
     * Stops scheduling the current run. Not instantaneous: a measurement already inside
     * libXray's blocking ping finishes on its own and its result is discarded. Rows still
     * testing return to idle.
     */
    public fun cancel() {
        dispatcher.cancel(runId.get())
        cache.finish(cache.testing.value)
    }

    /**
     * True once none of [profileIds] is still testing; false if [timeoutMillis] passes first.
     *
     * True means "no longer testing", not "measured": a later run may have cleared these ids
     * without producing results, so callers read [results] for what actually arrived.
     *
     * The timeout is the caller's bound on a run that never reports back (a dead binder),
     * so waiting on a measurement cannot hang auto-connect.
     */
    public suspend fun awaitDone(
        profileIds: Collection<Long>,
        timeoutMillis: Long,
    ): Boolean {
        val ids = profileIds.toSet()
        val finished =
            withTimeoutOrNull(timeoutMillis) {
                cache.testing.first { testing -> testing.none { it in ids } }
            }
        return finished != null
    }
}
