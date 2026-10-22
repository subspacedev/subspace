// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home.launch

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import space.getsub.core.data.automation.LaunchCandidate
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.PingMode
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StartupStage
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TrafficSample
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.automation.AutoConnectMode
import space.getsub.core.model.automation.LaunchTestScope
import space.getsub.core.model.automation.NetworkKind
import space.getsub.feature.home.TunnelConnection

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchAutomationTest {
    // Group 1 holds 1..3, group 2 holds 4..5. Names are placeholders, never real servers.
    private val servers =
        listOf(
            candidate(1, group = 1),
            candidate(2, group = 1),
            candidate(3, group = 1),
            candidate(4, group = 2),
            candidate(5, group = 2),
        )

    private val answers =
        mapOf(
            1L to LatencyResult.ok(80),
            2L to LatencyResult.ok(30),
            3L to LatencyResult.ok(50),
            4L to LatencyResult.ok(10),
            5L to LatencyResult.ok(90),
        )

    // ---- auto-connect off / Last picked ----------------------------------------------------

    @Test
    fun offDoesNotConnect() =
        runTest {
            val h = harness(settings(autoConnect = false), active = 1)
            h.automation.onFreshOpen()
            advanceUntilIdle()
            h.requests.shouldBeEmpty()
            h.latency.starts.shouldBeEmpty()
            h.automation.state.value shouldBe LaunchAutomationState.Idle
        }

    @Test
    fun lastPickedRequestsConnectForActive() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.LastPicked), active = 2)
            h.automation.onFreshOpen()
            advanceUntilIdle()
            h.requests.size shouldBe 1
            h.settings.writes.shouldBeEmpty()

            h.automation.onConsentGranted()
            advanceUntilIdle()
            h.tunnel.connected shouldBe listOf(2L)
        }

    @Test
    fun lastPickedWithNoActiveDoesNothing() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.LastPicked), active = null)
            h.automation.onFreshOpen()
            advanceUntilIdle()
            h.requests.shouldBeEmpty()
            h.automation.state.value shouldBe LaunchAutomationState.Idle
        }

    @Test
    fun connectedTunnelSkipsAutoConnect() =
        runTest {
            val busy =
                listOf(
                    ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 10808),
                    ConnectionState.Connecting(StartupStage.StartingCore),
                    ConnectionState.Disconnecting,
                )
            for (state in busy) {
                val h = harness(settings(mode = AutoConnectMode.FastestOverall), active = 1)
                h.tunnel.emit(state)
                h.automation.onFreshOpen()
                advanceUntilIdle()
                withClue(state::class.simpleName) {
                    h.requests.shouldBeEmpty()
                    h.latency.starts.shouldBeEmpty()
                }
            }
        }

    // ---- Fastest modes ---------------------------------------------------------------------

    @Test
    fun fastestInSubscriptionPicksLowestInActiveGroupAndMakesItActive() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.FastestInSubscription), active = 1)
            h.automation.onFreshOpen()
            advanceUntilIdle()

            h.latency.starts.single() shouldBe listOf(1L, 2L, 3L)
            h.settings.writes shouldBe listOf(2L)
            h.requests.size shouldBe 1
            h.automation.state.value shouldBe LaunchAutomationState.Idle

            h.automation.onConsentGranted()
            advanceUntilIdle()
            h.tunnel.connected shouldBe listOf(2L)
        }

    @Test
    fun fastestOverallSpansAllGroups() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.FastestOverall), active = 1)
            h.automation.onFreshOpen()
            advanceUntilIdle()

            h.latency.starts.single() shouldBe listOf(1L, 2L, 3L, 4L, 5L)
            h.settings.writes shouldBe listOf(4L)
            h.requests.size shouldBe 1
        }

    @Test
    fun fastestInSubscriptionWithNoActiveUsesAll() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.FastestInSubscription), active = null)
            h.automation.onFreshOpen()
            advanceUntilIdle()

            h.latency.starts.single() shouldBe listOf(1L, 2L, 3L, 4L, 5L)
            h.settings.writes shouldBe listOf(4L)
        }

    @Test
    fun fastestSkipsServersThatCannotConnect() =
        runTest {
            val list = servers.map { if (it.id == 4L) it.copy(connectable = false) else it }
            val h = harness(settings(mode = AutoConnectMode.FastestOverall), active = 1, list = list)
            h.automation.onFreshOpen()
            advanceUntilIdle()

            h.latency.starts.single() shouldBe listOf(1L, 2L, 3L, 5L)
            h.settings.writes shouldBe listOf(2L)
        }

    @Test
    fun fastestPassesEachSubscriptionsPingMode() =
        runTest {
            val list = servers.map { if (it.groupId == 2L) it.copy(mode = PingMode.PROXY_HEAD) else it }
            val h = harness(settings(mode = AutoConnectMode.FastestOverall), active = 1, list = list)
            h.automation.onFreshOpen()
            advanceUntilIdle()

            h.latency.modes.single() shouldBe mapOf(4L to PingMode.PROXY_HEAD, 5L to PingMode.PROXY_HEAD)
        }

    @Test
    fun markersExcludeOtherNetworkWhenOn() =
        runTest {
            val list = servers.map { if (it.id == 4L) it.copy(name = "Example LTE") else it }
            val h =
                harness(
                    settings(mode = AutoConnectMode.FastestOverall, useMarkers = true),
                    active = 1,
                    list = list,
                    network = NetworkKind.Wifi,
                )
            h.automation.onFreshOpen()
            advanceUntilIdle()

            h.settings.writes shouldBe listOf(2L)
        }

    @Test
    fun markersIgnoredWhenOff() =
        runTest {
            val list = servers.map { if (it.id == 4L) it.copy(name = "Example LTE") else it }
            val h =
                harness(
                    settings(mode = AutoConnectMode.FastestOverall, useMarkers = false),
                    active = 1,
                    list = list,
                    network = NetworkKind.Wifi,
                )
            h.automation.onFreshOpen()
            advanceUntilIdle()

            h.settings.writes shouldBe listOf(4L)
        }

    @Test
    fun noWinnerFallsBackToActiveIgnoringMarkers() =
        runTest {
            // The active server is marked for mobile and we are on Wi-Fi: still the fallback,
            // because it is the user's own pick.
            val list = servers.map { if (it.id == 1L) it.copy(name = "Example 5G") else it }
            val h =
                harness(
                    settings(mode = AutoConnectMode.FastestInSubscription),
                    active = 1,
                    list = list,
                    network = NetworkKind.Wifi,
                    answers = allFailed(LatencyOutcome.TIMEOUT),
                )
            h.automation.onFreshOpen()
            advanceUntilIdle()

            h.settings.writes.shouldBeEmpty()
            h.requests.size shouldBe 1
            h.automation.state.value shouldBe LaunchAutomationState.Idle
            h.automation.onConsentGranted()
            advanceUntilIdle()
            h.tunnel.connected shouldBe listOf(1L)
        }

    @Test
    fun noWinnerAndNoActiveShowsNoServerAnswered() =
        runTest {
            val h =
                harness(
                    settings(mode = AutoConnectMode.FastestOverall),
                    active = null,
                    answers = allFailed(LatencyOutcome.UNREACHABLE),
                )
            h.automation.onFreshOpen()
            advanceUntilIdle()

            h.requests.shouldBeEmpty()
            h.automation.state.value shouldBe LaunchAutomationState.NoServerAnswered
            h.automation.onNoteShown()
            h.automation.state.value shouldBe LaunchAutomationState.Idle
        }

    @Test
    fun foreignVpnSkipsTestAndConnectsActive() =
        runTest {
            val h =
                harness(
                    settings(mode = AutoConnectMode.FastestOverall, pingOnLaunch = true),
                    active = 3,
                    answers = allFailed(LatencyOutcome.FOREIGN_VPN),
                )
            h.automation.onFreshOpen()
            advanceUntilIdle()

            h.automation.state.value shouldBe LaunchAutomationState.ForeignVpnFallback
            h.settings.writes.shouldBeEmpty()
            h.requests.size shouldBe 1
            withClue("no launch test after the refused run: nothing is measurable") {
                h.latency.starts.size shouldBe 1
            }
            h.automation.onConsentGranted()
            advanceUntilIdle()
            h.tunnel.connected shouldBe listOf(3L)
        }

    @Test
    fun foreignVpnWithNoActiveKeepsTheNoteAndStops() =
        runTest {
            val h =
                harness(
                    settings(mode = AutoConnectMode.FastestOverall),
                    active = null,
                    answers = allFailed(LatencyOutcome.FOREIGN_VPN),
                )
            h.automation.onFreshOpen()
            advanceUntilIdle()

            h.automation.state.value shouldBe LaunchAutomationState.ForeignVpnFallback
            h.requests.shouldBeEmpty()
        }

    @Test
    fun aRefusedRunIgnoresOldResultsAndFallsBackToActive() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.FastestOverall), active = 2)
            h.latency.startReturns = false
            // From an earlier run this session: not this run's answer, so no note.
            h.latency.seed(allFailed(LatencyOutcome.FOREIGN_VPN))
            h.automation.onFreshOpen()
            advanceUntilIdle()

            h.automation.state.value shouldBe LaunchAutomationState.Idle
            h.settings.writes.shouldBeEmpty()
            h.requests.size shouldBe 1
            h.automation.onConsentGranted()
            advanceUntilIdle()
            h.tunnel.connected shouldBe listOf(2L)
        }

    @Test
    fun resultsFromAnEarlierRunDoNotDecideThePick() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.FastestInSubscription), active = 3)
            h.latency.seed(mapOf(1L to LatencyResult.ok(10)))
            h.latency.finishes = false
            h.automation.onFreshOpen()
            runCurrent()
            h.latency.deliver(2)
            // 1 and 3 never answer this run; the bound ends it.
            advanceUntilIdle()

            h.settings.writes shouldBe listOf(2L)
            h.requests.size shouldBe 1
        }

    @Test
    fun aRefusedRunLeavesEveryServerToTheLaunchTest() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.FastestOverall, pingOnLaunch = true), active = null)
            h.latency.startReturns = false
            h.automation.onFreshOpen()
            advanceUntilIdle()

            h.automation.state.value shouldBe LaunchAutomationState.NoServerAnswered
            h.latency.starts shouldBe listOf(listOf(1L, 2L, 3L, 4L, 5L), listOf(1L, 2L, 3L, 4L, 5L))
        }

    // ---- the service bind ------------------------------------------------------------------

    @Test
    fun aBindThatNeverArrivesEndsWithinTheBound() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.FastestOverall, timeoutSeconds = 5), active = 1)
            h.tunnel.bound.value = false
            h.automation.onFreshOpen()
            runCurrent()
            h.automation.state.value shouldBe LaunchAutomationState.Finding

            advanceTimeBy(7_999)
            h.automation.state.value shouldBe LaunchAutomationState.Finding
            advanceTimeBy(2)
            h.automation.state.value shouldBe LaunchAutomationState.Idle

            advanceUntilIdle()
            h.requests.shouldBeEmpty()
            h.settings.writes.shouldBeEmpty()
            h.latency.starts.shouldBeEmpty()
        }

    @Test
    fun stateIsReadAfterBindNotBefore() =
        runTest {
            for (mode in listOf(AutoConnectMode.LastPicked, AutoConnectMode.FastestOverall)) {
                val h = harness(settings(mode = mode), active = 1)
                // An unbound client reads its default Disconnected while the tunnel is up in :bg.
                h.tunnel.bound.value = false
                h.automation.onFreshOpen()
                runCurrent()
                h.tunnel.emit(ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 10808))
                h.tunnel.bound.value = true
                advanceUntilIdle()
                withClue(mode) {
                    h.requests.shouldBeEmpty()
                    h.settings.writes.shouldBeEmpty()
                    h.automation.state.value shouldBe LaunchAutomationState.Idle
                }
            }
        }

    @Test
    fun lastPickedWaitsForBind() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.LastPicked), active = 1)
            h.tunnel.bound.value = false
            h.automation.onFreshOpen()
            advanceTimeBy(1_000)
            h.requests.shouldBeEmpty()

            h.tunnel.bound.value = true
            advanceUntilIdle()
            h.requests.size shouldBe 1
        }

    @Test
    fun consentGrantedBeforeBindConnectsNothing() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.LastPicked), active = 1)
            h.automation.onFreshOpen()
            advanceUntilIdle()
            h.tunnel.bound.value = false
            h.automation.onConsentGranted()
            advanceUntilIdle()
            h.tunnel.connected.shouldBeEmpty()
        }

    @Test
    fun aRunThatNeverFinishesFallsBackWithinTheBound() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.FastestOverall, timeoutSeconds = 5), active = 1)
            h.latency.finishes = false
            h.automation.onFreshOpen()
            runCurrent()
            h.automation.state.value shouldBe LaunchAutomationState.Finding

            advanceUntilIdle()
            h.requestTimes shouldBe listOf(5_000L + 3_000L)
            h.settings.writes.shouldBeEmpty()
            h.automation.state.value shouldBe LaunchAutomationState.Idle
        }

    // ---- taking over -----------------------------------------------------------------------

    @Test
    fun cancelDuringFindingAbandonsChoiceButNotTheRun() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.FastestOverall), active = 1)
            h.latency.finishes = false
            h.automation.onFreshOpen()
            runCurrent()
            h.automation.state.value shouldBe LaunchAutomationState.Finding

            h.automation.cancel()
            runCurrent()
            h.automation.state.value shouldBe LaunchAutomationState.Idle
            withClue("the run in flight keeps going") { h.latency.testing.value shouldBe setOf(1L, 2L, 3L, 4L, 5L) }

            h.latency.finishNow()
            advanceUntilIdle()
            h.settings.writes.shouldBeEmpty()
            h.requests.shouldBeEmpty()
        }

    @Test
    fun aUserPickDuringTheChoiceAbandonsIt() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.FastestOverall), active = 1)
            h.latency.finishes = false
            h.automation.onFreshOpen()
            runCurrent()
            h.automation.state.value shouldBe LaunchAutomationState.Finding

            h.settings.userPicks(5)
            runCurrent()
            h.automation.state.value shouldBe LaunchAutomationState.Idle

            h.latency.finishNow()
            advanceUntilIdle()
            h.settings.writes.shouldBeEmpty()
            h.requests.shouldBeEmpty()
            h.latency.testing.value shouldBe emptySet()
        }

    @Test
    fun aManualConnectDuringTheChoiceAbandonsIt() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.FastestOverall), active = 1)
            h.latency.finishes = false
            h.automation.onFreshOpen()
            runCurrent()

            h.automation.onUserTookOver()
            h.latency.finishNow()
            advanceUntilIdle()
            h.automation.state.value shouldBe LaunchAutomationState.Idle
            h.settings.writes.shouldBeEmpty()
            h.requests.shouldBeEmpty()
        }

    @Test
    fun consentDeniedLeavesWinnerActiveAndIdle() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.FastestInSubscription), active = 1)
            h.automation.onFreshOpen()
            advanceUntilIdle()
            h.requests.size shouldBe 1

            h.automation.onConsentDenied()
            h.automation.onConsentGranted()
            advanceUntilIdle()
            h.settings.active.value shouldBe 2L
            h.tunnel.connected.shouldBeEmpty()
            h.automation.state.value shouldBe LaunchAutomationState.Idle
        }

    @Test
    fun consentGrantedAfterTheTunnelStartedConnectsNothing() =
        runTest {
            val h = harness(settings(mode = AutoConnectMode.LastPicked), active = 1)
            h.automation.onFreshOpen()
            advanceUntilIdle()
            h.tunnel.emit(ConnectionState.Connecting(StartupStage.StartingCore))
            h.automation.onConsentGranted()
            advanceUntilIdle()
            h.tunnel.connected.shouldBeEmpty()
        }

    // ---- launch test -----------------------------------------------------------------------

    @Test
    fun launchTestRunsOverChosenScopeWhenNoAutoConnect() =
        runTest {
            val scoped =
                harness(
                    settings(autoConnect = false, pingOnLaunch = true, scope = LaunchTestScope.ActiveSubscription),
                    active = 4,
                )
            scoped.automation.onFreshOpen()
            advanceUntilIdle()
            scoped.latency.starts shouldBe listOf(listOf(4L, 5L))

            val noActive =
                harness(
                    settings(autoConnect = false, pingOnLaunch = true, scope = LaunchTestScope.ActiveSubscription),
                    active = null,
                )
            noActive.automation.onFreshOpen()
            advanceUntilIdle()
            noActive.latency.starts shouldBe listOf(listOf(1L, 2L, 3L, 4L, 5L))

            val all =
                harness(
                    settings(autoConnect = false, pingOnLaunch = true, scope = LaunchTestScope.AllServers),
                    active = 4,
                )
            all.automation.onFreshOpen()
            advanceUntilIdle()
            all.latency.starts shouldBe listOf(listOf(1L, 2L, 3L, 4L, 5L))
            all.requests.shouldBeEmpty()
        }

    @Test
    fun launchTestRunsOnEveryFreshOpen() =
        runTest {
            val h = harness(settings(autoConnect = false, pingOnLaunch = true), active = 1)
            h.automation.onFreshOpen()
            advanceUntilIdle()
            h.automation.onFreshOpen()
            advanceUntilIdle()
            h.latency.starts.size shouldBe 2
        }

    @Test
    fun launchTestOffRunsNothing() =
        runTest {
            val h = harness(settings(autoConnect = false, pingOnLaunch = false), active = 1)
            h.automation.onFreshOpen()
            advanceUntilIdle()
            h.latency.starts.shouldBeEmpty()
        }

    @Test
    fun launchTestRespectsMeteredGate() =
        runTest {
            val gated = harness(settings(autoConnect = false, pingOnLaunch = true, metered = false), active = 1)
            gated.network.metered = true
            gated.automation.onFreshOpen()
            advanceUntilIdle()
            gated.latency.starts.shouldBeEmpty()

            val allowed = harness(settings(autoConnect = false, pingOnLaunch = true, metered = true), active = 1)
            allowed.network.metered = true
            allowed.automation.onFreshOpen()
            advanceUntilIdle()
            allowed.latency.starts.size shouldBe 1
        }

    @Test
    fun launchTestRespectsTheStartSequenceGate() =
        runTest {
            val busy = harness(settings(autoConnect = false, pingOnLaunch = true), active = 1)
            busy.tunnel.emit(ConnectionState.Connecting(StartupStage.StartingCore))
            busy.automation.onFreshOpen()
            advanceUntilIdle()
            busy.latency.starts.shouldBeEmpty()

            val connected = harness(settings(autoConnect = true, pingOnLaunch = true), active = 1)
            connected.tunnel.emit(ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 10808))
            connected.automation.onFreshOpen()
            advanceUntilIdle()
            withClue("settled Connected: no auto-connect, but the launch test runs") {
                connected.requests.shouldBeEmpty()
                connected.latency.starts.size shouldBe 1
            }
        }

    @Test
    fun fastestAlwaysTestsOnMetered() =
        runTest {
            val h =
                harness(
                    settings(mode = AutoConnectMode.FastestOverall, pingOnLaunch = true, metered = false),
                    active = 1,
                )
            h.network.metered = true
            h.automation.onFreshOpen()
            advanceUntilIdle()
            h.latency.starts.size shouldBe 1
            h.requests.size shouldBe 1
        }

    @Test
    fun launchTestSkippedWhenAutoConnectConnects() =
        runTest {
            val last = harness(settings(mode = AutoConnectMode.LastPicked, pingOnLaunch = true), active = 1)
            last.automation.onFreshOpen()
            advanceUntilIdle()
            last.latency.starts.shouldBeEmpty()
            last.requests.size shouldBe 1

            val fastest =
                harness(settings(mode = AutoConnectMode.FastestInSubscription, pingOnLaunch = true), active = 1)
            fastest.automation.onFreshOpen()
            advanceUntilIdle()
            withClue("only the Fastest run itself") { fastest.latency.starts shouldBe listOf(listOf(1L, 2L, 3L)) }
        }

    @Test
    fun launchTestSkipsWhatTheFastestRunMeasured() =
        runTest {
            val list = servers.map { if (it.id == 3L) it.copy(connectable = false) else it }
            val h =
                harness(
                    settings(mode = AutoConnectMode.FastestOverall, pingOnLaunch = true),
                    active = null,
                    list = list,
                    answers = allFailed(LatencyOutcome.TIMEOUT),
                )
            h.automation.onFreshOpen()
            advanceUntilIdle()
            h.automation.state.value shouldBe LaunchAutomationState.NoServerAnswered
            h.latency.starts shouldBe listOf(listOf(1L, 2L, 4L, 5L), listOf(3L))
        }

    // ---- harness ---------------------------------------------------------------------------

    private fun allFailed(outcome: LatencyOutcome): Map<Long, LatencyResult> =
        servers.associate { it.id to LatencyResult.failed(outcome) }

    // One named default per setting the tests vary; a builder would be more code for the same calls.
    @Suppress("LongParameterList")
    private fun settings(
        autoConnect: Boolean = true,
        mode: AutoConnectMode = AutoConnectMode.LastPicked,
        useMarkers: Boolean = true,
        scope: LaunchTestScope = LaunchTestScope.AllServers,
        pingOnLaunch: Boolean = false,
        metered: Boolean = false,
        timeoutSeconds: Int = 5,
    ) = LaunchSettingsSnapshot(autoConnect, mode, useMarkers, scope, pingOnLaunch, metered, timeoutSeconds)

    @Suppress("LongParameterList")
    private class Harness(
        val automation: LaunchAutomation,
        val settings: FakeSettings,
        val latency: FakeLatency,
        val tunnel: FakeTunnel,
        val network: FakeNetwork,
        val requests: List<Unit>,
        val requestTimes: List<Long>,
    )

    private fun TestScope.harness(
        snapshot: LaunchSettingsSnapshot,
        active: Long?,
        list: List<LaunchCandidate> = servers,
        network: NetworkKind = NetworkKind.Other,
        answers: Map<Long, LatencyResult> = this@LaunchAutomationTest.answers,
    ): Harness {
        val settings = FakeSettings(snapshot, active)
        val latency = FakeLatency(answers)
        val tunnel = FakeTunnel()
        val net = FakeNetwork(network)
        val automation =
            LaunchAutomation(
                // The test's own scope, not backgroundScope: advanceUntilIdle does not wait on
                // background work, and the sequence is what is under test.
                scope = this,
                settings = settings,
                servers = FakeServers(list),
                latency = latency,
                tunnel = tunnel,
                network = net,
            )
        val requests = mutableListOf<Unit>()
        val times = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            automation.connectRequests.collect {
                requests += it
                times += currentTime
            }
        }
        return Harness(automation, settings, latency, tunnel, net, requests, times)
    }

    private class FakeSettings(
        private val snapshot: LaunchSettingsSnapshot,
        initialActive: Long?,
    ) : LaunchSettings {
        val active = MutableStateFlow(initialActive)

        /** What the automation wrote; a user pick is not recorded here. */
        val writes = mutableListOf<Long>()

        fun userPicks(id: Long) {
            active.value = id
        }

        override suspend fun snapshot() = snapshot

        override val activeProfileId: Flow<Long?> = active

        override suspend fun activeProfileIdNow(): Long? = active.value

        override suspend fun setActiveProfile(id: Long) {
            writes += id
            active.value = id
        }
    }

    private class FakeServers(
        private val list: List<LaunchCandidate>,
    ) : LaunchServers {
        override suspend fun all() = list

        override suspend fun inGroup(groupId: Long) = list.filter { it.groupId == groupId }

        override suspend fun profile(id: Long): Profile? =
            list.firstOrNull { it.id == id && it.connectable }?.let {
                Profile(id = id.toString(), name = it.name, outbound = outbound)
            }
    }

    /** Behaves like LatencyRuns over LatencyCache: rows are marked testing until they finish. */
    private class FakeLatency(
        private val answers: Map<Long, LatencyResult>,
    ) : LaunchLatency {
        private val _results = MutableStateFlow<Map<Long, LatencyResult>>(emptyMap())
        override val results: StateFlow<Map<Long, LatencyResult>> = _results.asStateFlow()
        val testing = MutableStateFlow<Set<Long>>(emptySet())

        val starts = mutableListOf<List<Long>>()
        val modes = mutableListOf<Map<Long, PingMode>>()
        var finishes = true
        var startReturns = true

        fun seed(values: Map<Long, LatencyResult>) {
            _results.value = _results.value + values
        }

        private var onResult: ((Long, LatencyResult) -> Unit)? = null

        /** Delivers one row's answer to this run, as LatencyRuns does: cached, then the hook. */
        fun deliver(id: Long) {
            val result = answers.getValue(id)
            _results.value = _results.value + (id to result)
            testing.value = testing.value - id
            onResult?.invoke(id, result)
        }

        fun finishNow() {
            testing.value.filter { it in answers }.forEach(::deliver)
            testing.value = emptySet()
        }

        override suspend fun start(
            profileIds: List<Long>,
            modes: Map<Long, PingMode>,
            onResult: ((Long, LatencyResult) -> Unit)?,
        ): Boolean {
            starts += profileIds
            this.modes += modes
            // TunnelClient reports a refused run finished at once, so no row is left testing.
            if (!startReturns) return false
            this.onResult = onResult
            testing.value = testing.value + profileIds
            if (finishes) finishNow()
            return true
        }

        override suspend fun awaitDone(
            profileIds: Collection<Long>,
            timeoutMillis: Long,
        ): Boolean =
            withTimeoutOrNull(timeoutMillis) {
                testing.first { now -> now.none { it in profileIds } }
            } != null
    }

    private class FakeTunnel : TunnelConnection {
        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val state: StateFlow<ConnectionState> = _state.asStateFlow()
        override val bound = MutableStateFlow(true)
        val connected = mutableListOf<Long>()

        fun emit(next: ConnectionState) {
            _state.value = next
        }

        override fun connect(
            profile: Profile,
            rowId: Long,
        ) {
            connected += rowId
        }

        override fun disconnect() = Unit

        override val latencies: StateFlow<Map<Long, LatencyResult>> = MutableStateFlow(emptyMap())
        override val measuring: StateFlow<Set<Long>> = MutableStateFlow(emptySet())
        override val traffic: StateFlow<TrafficSample?> = MutableStateFlow(null)
        override val pingOnLaunch: Flow<Boolean> = flowOf(false)
        override val perTagBreakdown: Flow<Boolean> = flowOf(false)
        override val lightweightMode: Flow<Boolean> = flowOf(false)

        override suspend fun measure(profileId: Long) = Unit
    }

    private class FakeNetwork(
        var kind: NetworkKind,
    ) : NetworkKindReader {
        var metered = false

        override fun current() = kind

        override fun isMetered() = metered
    }

    private companion object {
        val outbound =
            VlessOutbound(
                address = "example.com",
                port = 443,
                uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab",
                flow = null,
                stream = StreamSettings(network = "tcp", security = Security.None),
            )

        fun candidate(
            id: Long,
            group: Long,
        ) = LaunchCandidate(
            id = id,
            groupId = group,
            name = "Server $id",
            connectable = true,
            mode = null,
            listIndex = id.toInt(),
        )
    }
}
