// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home.launch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import space.getsub.core.data.automation.LaunchCandidate
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.automation.AutoConnectMode
import space.getsub.core.model.automation.LaunchTestScope
import space.getsub.core.model.automation.PickCandidate
import space.getsub.core.model.automation.pickFastest
import space.getsub.feature.home.TunnelConnection
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What happens when the app's screen is created fresh (M10-C spec §6): auto-connect, then the
 * launch latency test.
 *
 * Application-scoped rather than a ViewModel's, so the activity can trigger it from `onCreate`
 * and Home can show its progress without either owning the job. Consent stays the activity's:
 * [connectRequests] asks for it, and [onConsentGranted] connects.
 *
 * Every call is expected on the main thread, and [scope] runs on `Dispatchers.Main.immediate`.
 * `LatencyRuns.start` is atomic only against other starts on the same thread, and Home and
 * Servers start theirs from the main thread.
 *
 * ARCHITECTURE.md §5.6: nothing here logs, so no server name can reach a log.
 */
// Six of these are the API Home and the activity call; the rest are the sequence's steps, one per
// spec case, which read better apart than folded into fewer, longer functions.
@Suppress("TooManyFunctions")
@Singleton
class LaunchAutomation
@Inject
internal constructor(
    @LaunchAutomationScope private val scope: CoroutineScope,
    private val settings: LaunchSettings,
    private val servers: LaunchServers,
    private val latency: LaunchLatency,
    private val tunnel: TunnelConnection,
    private val network: NetworkKindReader,
) {
    private val _state = MutableStateFlow<LaunchAutomationState>(LaunchAutomationState.Idle)
    val state: StateFlow<LaunchAutomationState> = _state.asStateFlow()

    // Conflated: one pending request is all that can matter, and it must survive the window
    // between onCreate and the activity starting to collect.
    private val requests = Channel<Unit>(Channel.CONFLATED)

    /** One event per auto-connect: run VPN consent, then call [onConsentGranted] or [onConsentDenied]. */
    val connectRequests: Flow<Unit> = requests.receiveAsFlow()

    private var job: Job? = null

    /** The profile the last request was for; cleared once consent is answered or the user acts. */
    private var chosen: Long? = null

    /** Runs the whole sequence. Called on every fresh open: there is no per-process latch. */
    fun onFreshOpen() {
        job?.cancel()
        chosen = null
        _state.value = LaunchAutomationState.Idle
        job = scope.launch { runSequence() }
    }

    /** Connects the chosen server, through the same tunnel path a Connect tap takes. */
    fun onConsentGranted() {
        val id = chosen ?: return
        chosen = null
        scope.launch {
            // Never on the client's default state: an unbound client reads Disconnected even
            // while the tunnel is up in :bg (ARCHITECTURE.md §5.5).
            if (!tunnel.bound.value) return@launch
            // Re-read, not assumed from when consent was asked: the dialog is asynchronous, and
            // the same reasoning as HomeViewModel.onConsentGranted applies.
            if (!tunnel.state.value.isIdle()) return@launch
            val profile = servers.profile(id) ?: return@launch
            tunnel.connect(profile, rowId = id)
        }
    }

    /** The winner stays active and nothing connects (M10-C spec §3.1, "Order with the consent dialog"). */
    fun onConsentDenied() {
        chosen = null
        if (_state.value == LaunchAutomationState.Finding) _state.value = LaunchAutomationState.Idle
    }

    /** Home's Cancel during "Finding the fastest server…". */
    fun cancel() = abandon()

    /**
     * A manual Connect or a server pick: the user's action wins. Abandons the choice only; a
     * latency run already in flight finishes and its results stay (M10-C spec §3.1), so Servers
     * never shows half-measured rows because of this.
     */
    fun onUserTookOver() = abandon()

    /** Home has shown [LaunchAutomationState.NoServerAnswered] or [LaunchAutomationState.ForeignVpnFallback]. */
    fun onNoteShown() {
        val now = _state.value
        if (now == LaunchAutomationState.NoServerAnswered || now == LaunchAutomationState.ForeignVpnFallback) {
            _state.value = LaunchAutomationState.Idle
        }
    }

    private fun abandon() {
        job?.cancel()
        job = null
        chosen = null
        if (_state.value == LaunchAutomationState.Finding) _state.value = LaunchAutomationState.Idle
    }

    // One early return per gate, each a distinct reason to stop.
    @Suppress("ReturnCount")
    private suspend fun runSequence() {
        val snapshot = settings.snapshot()
        // "The whole choice is bounded by the ping timeout plus a small margin" (M10-C spec §3.1),
        // and the same bound caps waiting for the service, so launch never hangs on a bind.
        val budget = snapshot.pingTimeoutSeconds * MILLIS + MARGIN_MILLIS
        if (snapshot.autoConnect && snapshot.mode != AutoConnectMode.LastPicked) {
            fastestSequence(snapshot, budget)
            return
        }
        // Until the handshake lands, tunnel.state is the client's default Disconnected, not the
        // service's: a tunnel still up in :bg after :main restarted would read as down
        // (ARCHITECTURE.md §5.5). No bind in time means the state is unknown, so do nothing.
        withTimeoutOrNull(budget) { tunnel.bound.first { it } } ?: return
        val state = tunnel.state.value
        // A start sequence under way: neither auto-connect nor the launch test (M10-C spec §3.1
        // and §3.2, which keeps the old Servers launch run's gate).
        if (state.isStartSequenceBusy()) return
        val all = servers.all()
        val active = activeIn(all)
        // Connected is settled but already connected: nothing to auto-connect, the test may run.
        // Last picked does not check markers: the user picked it (M10-C spec §3.3). The connect
        // is itself a start sequence, so the launch test would be refused by its gate anyway:
        // skipping it is M10-C spec §3.2's allowed choice.
        if (snapshot.autoConnect && state.isIdle() && active != null) {
            request(active.id)
            return
        }
        launchTest(snapshot, all, active, emptySet())
    }

    /** The stored active row, or null; a stored id that names no row (a deleted server) is none. */
    private suspend fun activeIn(all: List<LaunchCandidate>): LaunchCandidate? {
        val stored = settings.activeProfileIdNow()
        return all.firstOrNull { it.id == stored }
    }

    /**
     * A Fastest mode, from "Finding" to a request or a note. The bind wait, the state check,
     * reading the candidates and the run all sit inside one [budget], so the whole visible
     * "Finding" phase is capped, bind included.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private suspend fun fastestSequence(
        snapshot: LaunchSettingsSnapshot,
        budget: Long,
    ) {
        _state.value = LaunchAutomationState.Finding
        // This run's own results. LatencyCache keeps every earlier run's values too, and a stale
        // number must not decide the pick. Written from binder threads.
        val fresh = ConcurrentHashMap<Long, LatencyResult>()
        var bound = false
        var choice: Choice? = null
        val phase =
            withTimeoutOrNull(budget) {
                tunnel.bound.first { it }
                bound = true
                val state = tunnel.state.value
                if (state.isStartSequenceBusy()) return@withTimeoutOrNull Phase.Busy
                val all = servers.all()
                val storedActive = settings.activeProfileIdNow()
                val active = all.firstOrNull { it.id == storedActive }
                // With no active server, "Fastest in this subscription" uses all servers.
                val pool =
                    if (snapshot.mode == AutoConnectMode.FastestInSubscription && active != null) {
                        servers.inGroup(active.groupId)
                    } else {
                        all
                    }
                // Mirror what Connect refuses: undecodable rows and transports :core:xray cannot emit.
                val candidates = pool.filter { it.connectable }
                choice = Choice(all, active, candidates)
                if (!state.isIdle()) return@withTimeoutOrNull Phase.AlreadyConnected
                // "If there are no servers at all, it does nothing" (M10-C spec §3.1).
                if (candidates.isEmpty()) return@withTimeoutOrNull Phase.NoCandidates
                val ids = candidates.map { it.id }
                coroutineScope {
                    // A server pick elsewhere reaches us only as a changed active_profile_id;
                    // the automation has written nothing yet, so any change is the user's.
                    val watcher =
                        launch {
                            settings.activeProfileId.first { it != storedActive }
                            abandon()
                        }
                    // No metered gate: the user asked to be connected (M10-C spec §3.1, "Mobile data").
                    val started = latency.start(ids, candidates.modes()) { id, result -> fresh[id] = result }
                    if (started) latency.awaitDone(ids, budget)
                    // Stopped before any write below, so our own write is never mistaken for a pick.
                    watcher.cancel()
                }
                Phase.Measured
            }

        val chosenFrom = choice
        when {
            // No bind: the tunnel's state is unknown (ARCHITECTURE.md §5.5), so neither connect
            // nor test. Nothing has been written.
            !bound || chosenFrom == null || phase == Phase.Busy -> {
                _state.value = LaunchAutomationState.Idle
                return
            }

            phase == Phase.AlreadyConnected -> {
                _state.value = LaunchAutomationState.Idle
                launchTest(snapshot, chosenFrom.all, chosenFrom.active, emptySet())
                return
            }

            phase == Phase.NoCandidates -> {
                val outcome = fallBack(chosenFrom.active, emptySet(), noteIfNone = false)
                if (outcome is AutoConnectOutcome.NothingConnected) {
                    launchTest(snapshot, chosenFrom.all, chosenFrom.active, outcome.measured)
                }
                return
            }

            else -> Unit
        }
        // Measured, or the budget ran out mid-run: decide on what this run delivered.
        val results = HashMap(fresh)
        when (val outcome = decide(snapshot, chosenFrom, results)) {
            AutoConnectOutcome.Requested, AutoConnectOutcome.Stopped -> Unit
            is AutoConnectOutcome.NothingConnected ->
                launchTest(snapshot, chosenFrom.all, chosenFrom.active, outcome.measured)
        }
    }

    // One early return per gate, each a distinct reason to stop.
    @Suppress("ReturnCount")
    private suspend fun decide(
        snapshot: LaunchSettingsSnapshot,
        choice: Choice,
        results: Map<Long, LatencyResult>,
    ): AutoConnectOutcome {
        val ids = choice.candidates.map { it.id }
        // Ruling for this task: another VPN is read from the run's own results, not probed for
        // beforehand. Every candidate refused means nothing was measurable (M10-C spec §3.1).
        if (ids.all { results[it]?.outcome == LatencyOutcome.FOREIGN_VPN }) {
            _state.value = LaunchAutomationState.ForeignVpnFallback
            choice.active?.let { request(it.id) }
            return AutoConnectOutcome.Stopped
        }
        // On no winner, only ids with a fresh result count as measured: a refused or cut-short
        // run leaves the rest to the launch test.
        val winner =
            pickFastest(
                candidates = choice.candidates.map { PickCandidate(it.id, it.name, it.listIndex) },
                results = results,
                network = network.current(),
                useMarkers = snapshot.useMarkers,
                activeId = choice.active?.id,
            ) ?: return fallBack(choice.active, results.keys, noteIfNone = true)

        // The winner becomes "last picked", so Home shows what is connected (M10-C spec §3.1).
        settings.setActiveProfile(winner)
        request(winner)
        _state.value = LaunchAutomationState.Idle
        return AutoConnectOutcome.Requested
    }

    /** No winner: the active server, ignoring markers, since it is the user's own pick. */
    private fun fallBack(
        active: LaunchCandidate?,
        measured: Set<Long>,
        noteIfNone: Boolean,
    ): AutoConnectOutcome {
        if (active != null) {
            request(active.id)
            _state.value = LaunchAutomationState.Idle
            return AutoConnectOutcome.Requested
        }
        _state.value = if (noteIfNone) LaunchAutomationState.NoServerAnswered else LaunchAutomationState.Idle
        return AutoConnectOutcome.NothingConnected(measured)
    }

    @Suppress("ReturnCount")
    private suspend fun launchTest(
        snapshot: LaunchSettingsSnapshot,
        all: List<LaunchCandidate>,
        active: LaunchCandidate?,
        measured: Set<Long>,
    ) {
        if (!snapshot.pingOnLaunch) return
        // Re-read: the Fastest run above can take seconds.
        if (tunnel.state.value.isStartSequenceBusy()) return
        // A large list measured with proxy-head starts one Xray instance per server; doing that
        // unprompted on cellular is real data and battery (the old Servers launch run's gate,
        // kept by M10-C spec §3.2).
        if (network.isMetered() && !snapshot.pingOnLaunchMetered) return
        val pool =
            if (snapshot.testScope == LaunchTestScope.ActiveSubscription && active != null) {
                servers.inGroup(active.groupId)
            } else {
                all
            }
        // What the Fastest run measured is not tested twice (M10-C spec §3.2).
        val targets = pool.filter { it.id !in measured }
        if (targets.isEmpty()) return
        @Suppress("IgnoredReturnValue")
        latency.start(targets.map { it.id }, targets.modes())
    }

    private fun request(id: Long) {
        chosen = id
        requests.trySend(Unit)
    }

    /** What a Fastest sequence read before measuring. */
    private class Choice(
        val all: List<LaunchCandidate>,
        val active: LaunchCandidate?,
        val candidates: List<LaunchCandidate>,
    )

    private enum class Phase { Busy, AlreadyConnected, NoCandidates, Measured }

    private sealed interface AutoConnectOutcome {
        /** A connect request went out. */
        data object Requested : AutoConnectOutcome

        /** Nothing more to do this open: another VPN holds the route. */
        data object Stopped : AutoConnectOutcome

        /** Nothing will connect; the launch test may still run, minus [measured]. */
        data class NothingConnected(
            val measured: Set<Long>,
        ) : AutoConnectOutcome
    }

    private companion object {
        const val MILLIS = 1_000L

        /** "The ping timeout plus a small margin" (M10-C spec §3.1). */
        const val MARGIN_MILLIS = 3_000L
    }
}

/** `ping-type` is scoped to its subscription (ARCHITECTURE.md §A.1); absent means the global setting. */
private fun List<LaunchCandidate>.modes() = mapNotNull { c -> c.mode?.let { c.id to it } }.toMap()

private fun ConnectionState.isIdle(): Boolean = this is ConnectionState.Disconnected || this is ConnectionState.Failed

// Exhaustive with no `else`: M8 added Reconnecting and an `is`-chain once absorbed it silently. A
// state added later must not fall through whichever branch happens to catch it.
private fun ConnectionState.isStartSequenceBusy(): Boolean =
    when (this) {
        is ConnectionState.Connecting,
        ConnectionState.Disconnecting,
        is ConnectionState.Reconnecting,
        -> true

        is ConnectionState.Connected,
        ConnectionState.Disconnected,
        is ConnectionState.Failed,
        -> false
    }
