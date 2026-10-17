// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Tls
import space.getsub.core.model.LatencyOptions
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.Outbound
import space.getsub.core.model.PingMode
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.VlessOutbound
import space.getsub.core.xray.PingBatch
import space.getsub.core.xray.PingTarget

/**
 * How [LatencyRunner] groups proxy-head servers into `pingBatch` calls, and how
 * [planLatency]'s guards keep refused servers out of any batch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PingBatchSchedulingTest {
    private val fiveSeconds = LatencyOptions(PingMode.PROXY_HEAD, timeoutSeconds = 5, checkUrl = "https://a.invalid")
    private val threeSeconds = fiveSeconds.copy(timeoutSeconds = 3)
    private val otherUrl = fiveSeconds.copy(checkUrl = "https://b.invalid")
    private val tcp = LatencyOptions(PingMode.TCP, timeoutSeconds = 5, checkUrl = "https://a.invalid")

    private fun LatencyOptions.group() = PingGroup(timeoutSeconds, checkUrl)

    private data class SentBatch(
        val group: PingGroup,
        val keys: List<Long>,
    )

    /** Every profile id is its own profile; every one plans as batched under its options. */
    private fun batchingRunner(
        scope: TestScope,
        sent: MutableList<SentBatch>,
        onRunEnded: () -> Unit = {},
        measureBatch: suspend (PingGroup, List<PingTarget>) -> Map<Long, LatencyResult> = { _, targets ->
            targets.associate { it.key to LatencyResult.ok(it.key.toInt()) }
        },
    ) = LatencyRunner<Long>(
        measure = { _, _ -> error("nothing here is measured in place") },
        loadProfile = { id -> id },
        scope = scope,
        plan = { id, options -> LatencyPlan.Batched("config-$id", options.group()) },
        measureBatch = { group, targets ->
            sent += SentBatch(group, targets.map { it.key })
            measureBatch(group, targets)
        },
        onRunEnded = onRunEnded,
    )

    @Test
    fun serversAreBatchedByTimeoutAndUrlInChunksOfFive() =
        runTest {
            val sent = mutableListOf<SentBatch>()
            val delivered = mutableListOf<Long>()
            var finished = false
            val runner = batchingRunner(TestScope(testScheduler), sent)
            // 7 at 5 s / a, 2 at 3 s / a, 1 at 5 s / b — interleaved, so grouping is
            // not an accident of input order.
            val optionsByIndex =
                listOf(
                    fiveSeconds, threeSeconds, fiveSeconds, otherUrl, fiveSeconds,
                    fiveSeconds, threeSeconds, fiveSeconds, fiveSeconds, fiveSeconds,
                )

            runner.start(
                runId = 1L,
                profileIds = LongArray(optionsByIndex.size) { it + 100L },
                optionsFor = { optionsByIndex[it] },
                onResult = { _, id, _ -> delivered += id },
                onFinished = { finished = true },
            )
            advanceUntilIdle()

            val groupA = PingGroup(5, "https://a.invalid")
            val groupB = PingGroup(3, "https://a.invalid")
            val groupC = PingGroup(5, "https://b.invalid")
            sent.map { it.group to it.keys.size } shouldContainExactlyInAnyOrder
                listOf(groupA to 5, groupA to 2, groupB to 2, groupC to 1)
            // Two option groups never share a batch (Review Focus 3).
            sent.forEach { batch ->
                batch.keys.forEach { key -> optionsByIndex[key.toInt()].group() shouldBe batch.group }
                (batch.keys.size <= PingBatch.MAX_CONFIGS) shouldBe true
            }
            delivered.sorted() shouldContainExactly (100L..109L).toList()
            finished shouldBe true
        }

    @Test
    fun aBatchMissingAServersResultFailsThatServerRatherThanSkippingIt() =
        runTest {
            val results = mutableMapOf<Long, LatencyResult>()
            val runner =
                batchingRunner(TestScope(testScheduler), mutableListOf(), measureBatch = { _, _ -> emptyMap() })

            runner.start(1L, longArrayOf(7, 8), { fiveSeconds }, { _, id, r -> results[id] = r }, {})
            advanceUntilIdle()

            results.values.map { it.outcome } shouldContainExactly
                listOf(LatencyOutcome.UNREACHABLE, LatencyOutcome.UNREACHABLE)
        }

    @Test
    fun aThrowingBatchFailsItsServersAndDoesNotEscape() =
        runTest {
            val results = mutableMapOf<Long, LatencyResult>()
            var finished = false
            val scope = TestScope(testScheduler)
            val runner = batchingRunner(scope, mutableListOf(), measureBatch = { _, _ -> error("binder blew up") })

            runner.start(1L, longArrayOf(7, 8), { fiveSeconds }, { _, id, r -> results[id] = r }, { finished = true })
            advanceUntilIdle()

            results.keys shouldContainExactlyInAnyOrder listOf(7L, 8L)
            results.values.map { it.outcome }.distinct() shouldContainExactly listOf(LatencyOutcome.UNREACHABLE)
            finished shouldBe true
        }

    @Test
    fun aSupersededRunsLateBatchDoesNotLandOnTheNewRun() =
        runTest {
            val oldBatch = CompletableDeferred<Unit>()
            val delivered = mutableListOf<Pair<Long, Long>>()
            val runner =
                batchingRunner(TestScope(testScheduler), mutableListOf(), measureBatch = { _, targets ->
                    // The old run's batch: already inside :ping, so cancelling the
                    // coroutine cannot stop it — it completes after the new run began.
                    if (targets.size == 2) withContext(NonCancellable) { oldBatch.await() }
                    targets.associate { it.key to LatencyResult.ok(1) }
                })

            runner.start(1L, longArrayOf(1, 2), { fiveSeconds }, { run, id, _ -> delivered += run to id }, {})
            advanceUntilIdle()
            runner.start(2L, longArrayOf(9), { fiveSeconds }, { run, id, _ -> delivered += run to id }, {})
            advanceUntilIdle()
            oldBatch.complete(Unit)
            advanceUntilIdle()

            delivered shouldContainExactly listOf(2L to 9L)
        }

    @Test
    fun theRunEndHookFiresWhenARunFinishesIsSupersededOrIsCancelled() =
        runTest {
            var ended = 0
            val hold = CompletableDeferred<Unit>()
            val runner =
                batchingRunner(
                    TestScope(testScheduler),
                    mutableListOf(),
                    onRunEnded = { ended++ },
                    measureBatch = { _, t ->
                        hold.await()
                        t.associate { it.key to LatencyResult.ok(1) }
                    },
                )

            runner.start(1L, longArrayOf(1), { fiveSeconds }, { _, _, _ -> }, {})
            advanceUntilIdle()
            ended shouldBe 0
            runner.start(2L, longArrayOf(2), { fiveSeconds }, { _, _, _ -> }, {})
            ended shouldBe 1 // superseded
            runner.cancel(2L)
            ended shouldBe 2 // cancelled
            hold.complete(Unit)
            runner.start(3L, longArrayOf(3), { fiveSeconds }, { _, _, _ -> }, {})
            advanceUntilIdle()
            ended shouldBe 3 // finished
            runner.cancel(3L)
            ended shouldBe 3 // a cancel after the end releases nothing twice
        }

    @Test
    fun tcpModeNeverTouchesPingClient() =
        runTest {
            val connector = FakePingConnector()
            val client = PingClient(connector, TestScope(testScheduler))
            val results = mutableListOf<LatencyResult>()
            val runner =
                LatencyRunner<Long>(
                    measure = { _, _ -> LatencyResult.ok(12) },
                    loadProfile = { id -> id },
                    scope = TestScope(testScheduler),
                    plan = { _, options ->
                        planLatency(options, { false }, rawJson = null, outbound = vless) { "config" }
                    },
                    measureBatch = { group, targets -> client.measure(targets, group.timeoutSeconds, group.url) },
                    onRunEnded = client::releaseAfterGrace,
                )

            runner.start(1L, longArrayOf(1, 2, 3), { tcp }, { _, _, r -> results += r }, {})
            advanceUntilIdle()

            results.map { it.delayMillis } shouldContainExactly listOf(12, 12, 12)
            connector.binds shouldBe 0
        }

    // ── planLatency: the guards that stay ahead of any batch ──────────────────

    private val tls = Security.Tls(serverName = "example.com", fingerprint = "chrome", allowInsecure = false)

    private val vless: Outbound =
        VlessOutbound(
            address = "198.51.100.7",
            port = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            flow = null,
            stream = StreamSettings(network = "tcp", security = tls),
        )

    @Test
    fun aForeignVpnRefusesBeforeEitherMode() =
        runTest {
            for (options in listOf(tcp, fiveSeconds)) {
                planLatency(options, { true }, null, vless) { error("never generated") } shouldBe
                    LatencyPlan.Settled(LatencyResult.failed(LatencyOutcome.FOREIGN_VPN))
            }
        }

    // Final review, fix-before-merge minor: TcpProbe ignores a false protect(), so a VPN that
    // comes up after planning would have its own endpoint timed. Each TCP probe re-checks.
    @Test
    fun aForeignVpnAppearingAfterPlanningRefusesTheTcpProbe() =
        runTest {
            var foreignVpn = false
            planLatency(tcp, { foreignVpn }, null, vless) { error("never generated") } shouldBe LatencyPlan.InPlace

            foreignVpn = true
            var probed = false
            guardedTcpProbe({ foreignVpn }) {
                probed = true
                LatencyResult.ok(42)
            } shouldBe LatencyResult.failed(LatencyOutcome.FOREIGN_VPN)
            probed shouldBe false
        }

    @Test
    fun aTcpProbeRunsWhenNoForeignVpnHoldsTheRoute() =
        runTest {
            guardedTcpProbe({ false }) { LatencyResult.ok(42) } shouldBe LatencyResult.ok(42)
        }

    @Test
    fun eachInPlaceProbeOfARunRechecksTheForeignVpn() =
        runTest {
            var foreignVpn = false
            val results = mutableMapOf<Long, LatencyResult>()
            val runner =
                LatencyRunner<Long>(
                    measure = { id, _ ->
                        guardedTcpProbe({ foreignVpn }) {
                            // The VPN comes up while the first server is being measured.
                            foreignVpn = true
                            LatencyResult.ok(id.toInt())
                        }
                    },
                    loadProfile = { id -> id },
                    scope = this,
                    concurrency = 1,
                    plan = { _, options -> planLatency(options, { foreignVpn }, null, vless) { null } },
                )
            runner.start(
                runId = 1L,
                profileIds = longArrayOf(1L, 2L),
                optionsFor = { tcp },
                onResult = { _, id, result -> results[id] = result },
                onFinished = {},
            )
            advanceUntilIdle()

            results shouldBe mapOf(1L to LatencyResult.ok(1), 2L to LatencyResult.failed(LatencyOutcome.FOREIGN_VPN))
        }

    @Test
    fun aBalancerRowIsUnsupportedBeforeEitherMode() =
        runTest {
            val balancer =
                """{"routing":{"balancers":[{"tag":"b","selector":["proxy"]}],"rules":[{"network":"tcp,udp","balancerTag":"b"}]},
                   "outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"proxy-2","protocol":"vless"}]}"""
            for (options in listOf(tcp, fiveSeconds)) {
                planLatency(options, { false }, balancer, vless) { error("never generated") } shouldBe
                    LatencyPlan.Settled(LatencyResult.failed(LatencyOutcome.UNSUPPORTED))
            }
        }

    @Test
    fun anUnprojectableRawRowOrAnUnsupportedProtocolIsUnsupportedNotBatched() =
        runTest {
            planLatency(fiveSeconds, { false }, "{}", outbound = null) { error("never generated") } shouldBe
                LatencyPlan.Settled(LatencyResult.failed(LatencyOutcome.UNSUPPORTED))
            planLatency(fiveSeconds, { false }, null, vless) { null } shouldBe
                LatencyPlan.Settled(LatencyResult.failed(LatencyOutcome.UNSUPPORTED))
        }

    @Test
    fun plaintextVlessToAPublicServerIsUnsupportedForProxyHeadOnly() =
        runTest {
            val plaintext: Outbound =
                VlessOutbound("vpn.example.org", 443, "u", null, StreamSettings("tcp", Security.None))

            planLatency(fiveSeconds, { false }, null, plaintext) { error("never generated") } shouldBe
                LatencyPlan.Settled(LatencyResult.failed(LatencyOutcome.UNSUPPORTED))
            planLatency(tcp, { false }, null, plaintext) { error("never generated") } shouldBe LatencyPlan.InPlace
        }

    private val hysteria2: Outbound = Hysteria2Outbound("hy.example.com", 443, "pw", Hysteria2Tls("hy.example.com"))

    @Test
    fun hysteria2InTcpModeIsUnsupported() =
        runTest {
            // Not InPlace: a TCP connect to a QUIC server's port would time something that is not the server.
            planLatency(tcp, { false }, null, hysteria2) { error("never generated") } shouldBe
                LatencyPlan.Settled(LatencyResult.failed(LatencyOutcome.UNSUPPORTED))
            // A raw hysteria row arrives as its typed projection, with the raw bytes alongside.
            planLatency(tcp, { false }, """{"outbounds":[{"protocol":"hysteria"}]}""", hysteria2) {
                error("never generated")
            } shouldBe LatencyPlan.Settled(LatencyResult.failed(LatencyOutcome.UNSUPPORTED))
        }

    @Test
    fun onlyHysteria2IsAUdpServer() {
        isUdpServer(hysteria2) shouldBe true
        isUdpServer(vless) shouldBe false
        isUdpServer(null) shouldBe false
    }

    @Test
    fun hysteria2ProxyHeadIsBatched() =
        runTest {
            val plan = planLatency(fiveSeconds, { false }, null, hysteria2) { "hy-config" }

            plan.shouldBeInstanceOf<LatencyPlan.Batched>()
            plan.xrayJson shouldBe "hy-config"
            plan.group shouldBe PingGroup(5, "https://a.invalid")
        }

    @Test
    fun aProxyHeadServerIsBatchedUnderItsTimeoutAndUrl() =
        runTest {
            val plan = planLatency(fiveSeconds, { false }, null, vless) { "the-config" }

            plan.shouldBeInstanceOf<LatencyPlan.Batched>()
            plan.group shouldBe PingGroup(5, "https://a.invalid")
            plan.xrayJson shouldBe "the-config"
            // ARCHITECTURE.md §5.6: the config never reaches a toString.
            plan.toString().contains("the-config") shouldBe false
        }
}
