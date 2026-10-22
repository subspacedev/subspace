// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service.latency

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import space.getsub.core.data.LatencyCache
import space.getsub.core.model.LatencyOptions
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.LatencyTarget
import space.getsub.core.model.PingMode

@OptIn(ExperimentalCoroutinesApi::class)
class LatencyRunsTest {
    private class Started(
        val runId: Long,
        val targets: List<LatencyTarget>,
        val onResult: (Long, LatencyResult) -> Unit,
        val onFinished: () -> Unit,
    )

    private class FakeDispatcher : LatencyDispatcher {
        val started = mutableListOf<Started>()
        val cancelled = mutableListOf<Long>()
        var bound = true

        override fun start(
            runId: Long,
            targets: List<LatencyTarget>,
            options: LatencyOptions,
            onResult: (Long, LatencyResult) -> Unit,
            onFinished: () -> Unit,
        ): Boolean {
            started += Started(runId, targets, onResult, onFinished)
            // TunnelClient.startLatencyRun reports onFinished itself when the run never reaches :bg.
            if (!bound) onFinished()
            return bound
        }

        override fun cancel(runId: Long) {
            cancelled += runId
        }
    }

    private val cache = LatencyCache()
    private val dispatcher = FakeDispatcher()
    private val options = LatencyOptions(PingMode.TCP, timeoutSeconds = 1, checkUrl = "https://x.invalid")
    private var optionsGate: CompletableDeferred<Unit>? = null
    private val runs =
        LatencyRuns(dispatcher, cache) {
            optionsGate?.also { gate ->
                optionsGate = null
                gate.await()
            }
            options
        }

    private val ok = LatencyResult(42, LatencyOutcome.OK)

    @Test
    fun onResultSeesThisRunsResultsAfterTheyAreCached() =
        runTest {
            cache.put(9, ok)
            val seen = mutableListOf<Pair<Long, LatencyResult>>()
            runs.start(listOf(1, 2), emptyMap()) { id, result ->
                // Cached first, so a reader of results never lags the hook.
                runs.results.value[id] shouldBe result
                seen += id to result
            }

            dispatcher.started.single().onResult(2, ok)

            seen shouldBe listOf(2L to ok)
        }

    @Test
    fun startSupersedesAndClearsTheOldRowsTesting() =
        runTest {
            runs.start(listOf(1, 2), emptyMap())
            runs.testing.value shouldBe setOf(1L, 2L)

            runs.start(listOf(3), emptyMap())

            // The first run never reaches its own onFinished, so only the clear in start can release 1 and 2.
            runs.testing.value shouldBe setOf(3L)
        }

    @Test
    fun currentRunMovesOnEveryStartButNotOnCancel() =
        runTest {
            runs.start(listOf(1), emptyMap())
            val first = runs.currentRun
            first shouldBe dispatcher.started.single().runId

            runs.cancel()
            runs.currentRun shouldBe first

            runs.start(listOf(2), emptyMap())
            runs.currentRun shouldBe dispatcher.started.last().runId
            (runs.currentRun > first) shouldBe true
        }

    @Test
    fun perProfileModeOverridesTheGlobalOne() =
        runTest {
            runs.start(listOf(1, 2), mapOf(2L to PingMode.PROXY_HEAD))

            dispatcher.started.single().targets shouldBe
                listOf(LatencyTarget(1, PingMode.TCP), LatencyTarget(2, PingMode.PROXY_HEAD))
        }

    @Test
    fun awaitDoneReturnsTrueOnceRowsFinish() =
        runTest {
            runs.start(listOf(1, 2), emptyMap())
            var done: Boolean? = null
            launch { done = runs.awaitDone(listOf(1, 2), 10_000) }
            runCurrent()
            done shouldBe null

            val run = dispatcher.started.single()
            run.onResult(1, ok)
            runCurrent()
            done shouldBe null

            run.onFinished()
            runCurrent()
            done shouldBe true
        }

    @Test
    fun awaitDoneReturnsAtOnceWhenNothingIsTesting() =
        runTest {
            runs.awaitDone(listOf(1), 10_000) shouldBe true
        }

    @Test
    fun awaitDoneTimesOut() =
        runTest {
            runs.start(listOf(1), emptyMap())
            var done: Boolean? = null
            launch { done = runs.awaitDone(listOf(1), 5_000) }
            advanceTimeBy(4_999)
            done shouldBe null
            advanceTimeBy(2)
            done shouldBe false
        }

    @Test
    fun cancellingAbandonedChoiceLeavesNoRowTesting() =
        runTest {
            runs.start(listOf(1, 2), emptyMap())

            runs.cancel()

            runs.testing.value.shouldBeEmpty()
            dispatcher.cancelled shouldBe listOf(dispatcher.started.single().runId)
        }

    @Test
    fun homeMeasureAndServersTestShareOneFence() =
        runTest {
            // A Servers run over a group, then Home measuring one profile, as the two callers do.
            runs.start(listOf(1, 2, 3), emptyMap())
            runs.start(listOf(2), emptyMap())

            val (servers, home) = dispatcher.started
            (home.runId > servers.runId) shouldBe true
            runs.testing.value shouldBe setOf(2L)

            home.onResult(2, ok)
            home.onFinished()
            runs.testing.value.shouldBeEmpty()
            runs.results.value[2] shouldBe ok
        }

    @Test
    fun overlappingStartsLeaveOnlyTheLaterRunsRowsTesting() =
        runTest {
            // The first start suspends in option resolution while the second runs to completion.
            optionsGate = CompletableDeferred()
            val gate = optionsGate!!
            launch { runs.start(listOf(1, 2), emptyMap()) }
            runCurrent()
            runs.start(listOf(3), emptyMap())

            gate.complete(Unit)
            runCurrent()

            // The first run was dispatched last, so it is the live one; the second was superseded
            // and its rows must not be left on "…".
            val live = dispatcher.started.last()
            runs.testing.value shouldBe live.targets.map { it.profileId }.toSet()

            live.onFinished()
            runs.testing.value.shouldBeEmpty()
        }

    @Test
    fun aStartThatNeverReachedTheServiceLeavesNoRowTesting() =
        runTest {
            dispatcher.bound = false

            runs.start(listOf(1, 2), emptyMap()) shouldBe false

            runs.testing.value.shouldBeEmpty()
        }
}
