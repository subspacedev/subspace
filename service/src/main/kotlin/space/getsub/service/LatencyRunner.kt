// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import space.getsub.core.model.LatencyOptions
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.xray.PingBatch
import space.getsub.core.xray.PingTarget

/** One `pingBatch` call's shared options: libXray takes one timeout and one URL per call. */
internal data class PingGroup(
    val timeoutSeconds: Int,
    val url: String,
)

/** How one server in a run is to be measured, decided before any measuring starts. */
internal sealed interface LatencyPlan {
    /** Measured here, one at a time under the runner's permit (TCP). */
    data object InPlace : LatencyPlan

    /** Decided without measuring: a guard refused it. */
    data class Settled(
        val result: LatencyResult,
    ) : LatencyPlan

    /**
     * Sent to `:ping` in a `pingBatch` with every other server in [group]. [certProbe]: if it
     * fails, `:ping` may run the certificate probe on it (M9-D1 spec §2) — `:bg` decides who,
     * `:ping` never inspects a config to decide.
     */
    class Batched(
        val xrayJson: String,
        val group: PingGroup,
        val certProbe: Boolean = false,
    ) : LatencyPlan {
        // ARCHITECTURE.md §5.6: the config carries the address, UUID and keys.
        override fun toString(): String = "Batched(group=$group, xrayJson=<redacted>, certProbe=$certProbe)"
    }
}

/**
 * Runs one measurement run in `:bg`.
 *
 * Generic in the profile type so the scheduling, fencing and cancellation logic
 * is testable on the JVM without Room or libXray; [TunnelService] binds it to
 * `StoredProfile`.
 *
 * ## Why a run id and not a cancellable job alone
 *
 * A TCP probe blocks in a socket connect, and a proxy-head batch, once sent,
 * runs to completion inside `:ping`'s blocking `pingBatch` call into Go, so
 * cancelling the coroutine stops *scheduling* but cannot stop a measurement
 * already under way. That measurement still completes and still
 * wants to deliver. Fencing every delivery on [activeRunId] is what keeps a
 * superseded run's late result from landing on a row the next run is currently
 * retesting — which would show the user a number from the wrong test.
 */
// LongParameterList: every parameter is a separate seam the JVM tests replace,
// and each defaults so a test names only the ones it exercises.
@Suppress("LongParameterList")
internal class LatencyRunner<T>(
    private val measure: suspend (T, LatencyOptions) -> LatencyResult,
    private val loadProfile: suspend (Long) -> T?,
    private val scope: CoroutineScope,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    /**
     * Reports a swallowed measurement failure.
     *
     * A hook rather than a direct `android.util.Log` call, for two reasons. This
     * class is deliberately free of Android types so its scheduling and fencing
     * logic is testable on the JVM — and `Log` is a stub off-device that *throws*,
     * so calling it from the catch below would have let the very exception this
     * guard exists to contain escape after all. The test that found that is the
     * one asserting nothing reaches the scope's handler.
     *
     * Takes a class name, never a message: a Room or libXray error can quote a
     * stored config value (ARCHITECTURE.md §5.6).
     */
    private val onMeasurementError: (String) -> Unit = {},
    /**
     * Decides each server's [LatencyPlan] once its profile is loaded. The default
     * measures everything in place through [measure].
     */
    private val plan: suspend (T, LatencyOptions) -> LatencyPlan = { _, _ -> LatencyPlan.InPlace },
    /**
     * Measures one batch of at most [PingBatch.MAX_CONFIGS] servers sharing a
     * [PingGroup]; keyed by [PingTarget.key]. A missing key fails that server.
     */
    private val measureBatch: suspend (PingGroup, List<PingTarget>) -> Map<Long, LatencyResult> =
        { _, _ -> emptyMap() },
    /**
     * Called, under the runner's lock, when the active run finishes, is
     * superseded, or is cancelled — the moment `:bg`'s grace before letting go
     * of `:ping` starts (Ruling P8).
     */
    private val onRunEnded: () -> Unit = {},
) {
    /**
     * One semaphore for the runner, not one per run.
     *
     * A superseded run's measurements cannot be interrupted — that is the whole
     * reason the run-id fence exists — so a per-run semaphore let the old run's
     * permits and the new run's permits coexist, allowing up to twice
     * [concurrency] measurements at once. A bound that doubles under ordinary
     * supersession is not a bound.
     */
    private val gate = Semaphore(concurrency)

    private val lock = Any()

    // Both guarded by `lock`.
    private var job: Job? = null
    private var activeRunId: Long = NO_RUN

    /**
     * One server through the run.
     *
     * [key] is the server's index in the run, not its profile id: a batch maps
     * results back by key, and an id repeated in one request must not collapse two
     * servers into one result.
     */
    private class Item<T>(
        val key: Long,
        val profileId: Long,
        val profile: T?,
        val options: LatencyOptions?,
        val plan: LatencyPlan,
    )

    /**
     * Starts [runId], superseding whatever ran before.
     *
     * Every server is planned first — profile load and guards, no network — so
     * proxy-head servers can be grouped by [PingGroup] and chunked into batches of
     * [PingBatch.MAX_CONFIGS]. Then every in-place measurement and every batch is
     * scheduled under the one [gate].
     */
    fun start(
        runId: Long,
        profileIds: LongArray,
        optionsFor: (Int) -> LatencyOptions,
        onResult: (Long, Long, LatencyResult) -> Unit,
        onFinished: (Long) -> Unit,
    ) {
        val previous =
            synchronized(lock) {
                val old = job
                // Superseding a live run ends it; this run's first batch cancels
                // the grace and reuses the binding.
                if (activeRunId != NO_RUN) onRunEnded()
                activeRunId = runId
                old
            }
        // Cancels scheduling only. A measurement already under way runs to
        // completion; its result is dropped by the fence in `deliver`.
        previous?.cancel()

        fun deliver(
            profileId: Long,
            result: LatencyResult,
        ) {
            if (isCurrent(runId)) onResult(runId, profileId, result)
        }

        val started =
            scope.launch {
                val items =
                    profileIds.mapIndexed { index, profileId ->
                        planned(index, profileId, optionsFor).also { item ->
                            (item.plan as? LatencyPlan.Settled)?.let { deliver(profileId, it.result) }
                        }
                    }
                val inPlace =
                    items.filter { it.plan == LatencyPlan.InPlace }.map { item ->
                        async {
                            gate.withPermit {
                                val profile = item.profile
                                val options = item.options
                                // InPlace is planned only for a loaded profile with options.
                                val result =
                                    if (profile != null && options != null) {
                                        guardedOr(FAILED) { measure(profile, options) }
                                    } else {
                                        FAILED
                                    }
                                deliver(item.profileId, result)
                            }
                        }
                    }
                val batches = scheduleBatches(items, ::deliver)
                (inPlace + batches).awaitAll()
                if (isCurrent(runId)) onFinished(runId)
                synchronized(lock) {
                    if (activeRunId == runId) {
                        activeRunId = NO_RUN
                        job = null
                        onRunEnded()
                    }
                }
            }

        synchronized(lock) {
            // Only claim the slot if nothing superseded us while we were starting.
            if (activeRunId == runId) job = started
        }
    }

    /**
     * Groups [items]' batched servers by [PingGroup] — libXray takes one timeout
     * and one URL per call — and schedules each chunk of [PingBatch.MAX_CONFIGS]
     * under the [gate].
     */
    private fun CoroutineScope.scheduleBatches(
        items: List<Item<T>>,
        deliver: (Long, LatencyResult) -> Unit,
    ): List<Deferred<Unit>> =
        items
            .filter { it.plan is LatencyPlan.Batched }
            .groupBy { (it.plan as LatencyPlan.Batched).group }
            .flatMap { (group, members) -> members.chunked(PingBatch.MAX_CONFIGS).map { group to it } }
            .map { (group, chunk) ->
                async {
                    gate.withPermit {
                        val targets =
                            chunk.map {
                                val batched = it.plan as LatencyPlan.Batched
                                PingTarget(it.key, batched.xrayJson, batched.certProbe)
                            }
                        val results = guardedOr(emptyMap()) { measureBatch(group, targets) }
                        chunk.forEach { deliver(it.profileId, results[it.key] ?: FAILED) }
                    }
                }
            }

    /**
     * Loads and plans one server. Deleted between the list rendering and the run
     * reaching it is a real outcome, not a silent skip: §10.4 — the row must stop
     * saying "testing".
     */
    private suspend fun planned(
        index: Int,
        profileId: Long,
        optionsFor: (Int) -> LatencyOptions,
    ): Item<T> {
        var profile: T? = null
        var options: LatencyOptions? = null
        val decided =
            guardedOr<LatencyPlan>(LatencyPlan.Settled(FAILED)) {
                val resolved = optionsFor(index).also { options = it }
                loadProfile(profileId)
                    ?.also { profile = it }
                    ?.let { plan(it, resolved) }
                    ?: LatencyPlan.Settled(FAILED)
            }
        return Item(index.toLong(), profileId, profile, options, decided)
    }

    // The broad catches below are deliberate and are the whole point of the guard:
    // `scope` belongs to TunnelService and carries the start-sequence exception
    // handler, so *any* escaping type — Room's SQLiteException, an
    // IllegalArgumentException from a stored enum name, a socket failure under fd
    // pressure, a binder failure reaching `:ping` — publishes a fabricated tunnel
    // failure (a `CoreStartFailed` while the tunnel still carries traffic,
    // ARCHITECTURE.md §5.5's lying UI) and leaves currentState `Failed`, which
    // opens startTunnel's duplicate-connect guard and leaks the live TUN fd
    // (§5.4). Naming individual types would leave exactly the gaps this exists to
    // close. CancellationException is rethrown: swallowing it would break
    // supersession, which is cooperative.
    @Suppress("TooGenericExceptionCaught")
    private inline fun <R> guardedOr(
        failure: R,
        block: () -> R,
    ): R =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onMeasurementError(e.javaClass.simpleName)
            failure
        }

    /**
     * Stops scheduling for [runId].
     *
     * A stale cancel — one naming a run that has already been superseded — is
     * ignored rather than tearing down whatever is running now.
     */
    fun cancel(runId: Long) {
        val toCancel =
            synchronized(lock) {
                if (activeRunId != runId) return
                activeRunId = NO_RUN
                onRunEnded()
                job.also { job = null }
            }
        toCancel?.cancel()
    }

    private fun isCurrent(runId: Long): Boolean = synchronized(lock) { activeRunId == runId }

    companion object {
        /**
         * How many permits — one per TCP probe, one per proxy-head batch — run at
         * once.
         *
         * Proxy-head no longer holds a `core.Instance` in `:bg`, so this is no
         * longer a memory bound. It does not set proxy-head parallelism either:
         * [PingClient] keeps one batch in flight, because `:ping` runs them one at a
         * time (`coreServerMu`), so that path probes five servers at once whatever
         * this says. Four stays for TCP, where it is what takes a 40-server group
         * from minutes to seconds.
         */
        const val DEFAULT_CONCURRENCY = 4

        private val FAILED = LatencyResult.failed(LatencyOutcome.UNREACHABLE)

        /**
         * Below every id any caller mints. `:main` derives Home's run id as
         * `-profileId`, so `-1` — the obvious sentinel — is exactly the id of a run
         * for the first row Room ever inserted. `isCurrent` would then pass for a
         * superseded Home measurement after a cancel, and the whole design rests on
         * that fence being inviolable.
         */
        const val NO_RUN = Long.MIN_VALUE
    }
}
