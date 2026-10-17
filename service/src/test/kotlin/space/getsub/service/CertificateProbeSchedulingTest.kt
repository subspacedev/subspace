// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
import space.getsub.core.xray.CertificateProbe
import space.getsub.core.xray.CertificateProbeResult
import space.getsub.core.xray.PingBatchCall
import space.getsub.core.xray.PingTarget

/**
 * Which servers `:ping` probes for an untrusted certificate, and what the answer then says
 * (M9-D1 spec §2, "How the error is obtained").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CertificateProbeSchedulingTest {
    private val url = "https://x.invalid/204"

    /** Every config is `cfg-<key>`; [ok] keys measure, the rest fail. */
    private fun call(vararg ok: Long) =
        PingBatchCall { payload ->
            val keys = Regex("cfg-(\\d+)").findAll(payload).map { it.groupValues[1].toLong() }.toList()
            keys.joinToString(",", """{"results":[""", "]}") { key ->
                if (key in ok) """{"success":true,"delay":50}""" else """{"success":false,"delay":10000}"""
            }
        }

    private fun request(
        keys: List<Long>,
        probe: Set<Long>,
    ) = PingBatchParcel.of(keys.map { PingTarget(it, "cfg-$it", certProbe = it in probe) }, 5, url)

    private class Probes(
        private val answer: (String) -> CertificateProbeResult = { CertificateProbeResult.UNTRUSTED },
    ) {
        val probed = mutableListOf<String>()
        val urls = mutableSetOf<String>()

        fun probe(
            xrayJson: String,
            url: String,
        ): CertificateProbeResult {
            probed += xrayJson
            urls += url
            return answer(xrayJson)
        }
    }

    @Test
    fun onlyAFailedEligibleServerIsProbedAndItsOutcomeIsNamed() {
        val probes = Probes()
        // 1: eligible and failed. 2: eligible but measured. 3: failed but not eligible (not a UDP server).
        val reply = answerPingBatch(request(listOf(1, 2, 3), probe = setOf(1, 2)), call(2), probe = probes::probe)

        probes.probed shouldContainExactly listOf("cfg-1")
        probes.urls shouldBe setOf(url)
        reply.toResults() shouldBe
            mapOf(
                1L to LatencyResult.failed(LatencyOutcome.CERT_UNTRUSTED),
                2L to LatencyResult.ok(50),
                3L to LatencyResult.failed(LatencyOutcome.UNREACHABLE),
            )
    }

    @Test
    fun atMostThreeServersAreProbedPerBatch() {
        val probes = Probes()
        val keys = listOf(1L, 2L, 3L, 4L, 5L)

        val reply = answerPingBatch(request(keys, probe = keys.toSet()), call(), probe = probes::probe)

        probes.probed.size shouldBe CertificateProbe.MAX_PER_BATCH
        probes.probed shouldContainExactly listOf("cfg-1", "cfg-2", "cfg-3")
        reply.toResults().filterValues { it.outcome == LatencyOutcome.CERT_UNTRUSTED }.keys shouldBe setOf(1L, 2L, 3L)
        reply.toResults().getValue(4L).outcome shouldBe LatencyOutcome.UNREACHABLE
    }

    @Test
    fun aProbeThatFindsNoCertificateFailureOrCannotRunKeepsTheOriginalFailure() {
        val answers =
            mapOf(
                "cfg-1" to CertificateProbeResult.NOT_A_CERTIFICATE_FAILURE,
                "cfg-2" to CertificateProbeResult.DID_NOT_RUN,
            )
        val reply =
            answerPingBatch(
                request(listOf(1, 2), probe = setOf(1, 2)),
                call(),
                probe = Probes { answers.getValue(it) }::probe,
            )

        reply.toResults().values.map { it.outcome }.distinct() shouldContainExactly listOf(LatencyOutcome.UNREACHABLE)
    }

    @Test
    fun aProbeThatThrowsKeepsTheOriginalFailureAndReportsTheClassNameOnly() {
        val reported = mutableListOf<String>()
        val reply =
            answerPingBatch(
                request(listOf(1), probe = setOf(1)),
                call(),
                probe = { _, _ -> throw IllegalStateException("cfg-1 secret") },
            ) { reported += it }

        reply.toResults().getValue(1L).outcome shouldBe LatencyOutcome.UNREACHABLE
        reported shouldContainExactly listOf("IllegalStateException")
    }

    @Test
    fun aFailedStopEndsProbingForTheBatch() {
        val probes = Probes { CertificateProbeResult.STOP_FAILED }

        val reply = answerPingBatch(request(listOf(1, 2, 3), probe = setOf(1, 2, 3)), call(), probe = probes::probe)

        probes.probed shouldContainExactly listOf("cfg-1")
        reply.toResults().values.map { it.outcome }.distinct() shouldContainExactly listOf(LatencyOutcome.UNREACHABLE)
    }

    @Test
    fun noProbeRunsWithoutAProber() {
        // The default: answerPingBatch's existing callers probe nothing.
        answerPingBatch(request(listOf(1), probe = setOf(1)), call()).toResults().getValue(1L).outcome shouldBe
            LatencyOutcome.UNREACHABLE
    }

    // ── The worker's poison rule ─────────────────────────────────────────────

    @Test
    fun afterAFailedStopTheWorkerNeverCallsPingBatchAgain() {
        var calls = 0
        val counting =
            PingBatchCall { payload ->
                calls++
                call().invoke(payload)
            }
        val worker = PingBatchWorker(counting, probe = { _, _ -> CertificateProbeResult.STOP_FAILED })

        worker.answer(request(listOf(1), probe = setOf(1)))
        worker.poisoned shouldBe true
        val second = worker.answer(request(listOf(2), probe = emptySet()))

        calls shouldBe 1
        second.toResults().getValue(2L).outcome shouldBe LatencyOutcome.UNREACHABLE
    }

    @Test
    fun theProbeDirectoryIsSweptBeforeEveryBatchPoisonedOrNot() {
        val events = mutableListOf<String>()
        val worker =
            PingBatchWorker(
                call = { payload ->
                    events += "pingBatch"
                    call().invoke(payload)
                },
                probe = { _, _ ->
                    events += "probe"
                    CertificateProbeResult.STOP_FAILED
                },
                sweep = { events += "sweep" },
            )

        worker.answer(request(listOf(1), probe = setOf(1)))
        worker.answer(request(listOf(2), probe = setOf(2)))

        events shouldContainExactly listOf("sweep", "pingBatch", "probe", "sweep")
    }

    @Test
    fun aCleanProbeLeavesTheWorkerUsable() {
        val worker = PingBatchWorker(call(), probe = { _, _ -> CertificateProbeResult.UNTRUSTED })

        worker.answer(request(listOf(1), probe = setOf(1))).toResults().getValue(1L).outcome shouldBe
            LatencyOutcome.CERT_UNTRUSTED
        worker.poisoned shouldBe false
    }

    @Test
    fun theWorkersLogLinesNeverCarryText() {
        val lines = mutableListOf<String>()
        val results =
            listOf(
                CertificateProbeResult.UNTRUSTED,
                CertificateProbeResult.NOT_A_CERTIFICATE_FAILURE,
                CertificateProbeResult.DID_NOT_RUN,
                CertificateProbeResult.STOP_FAILED,
            ).iterator()
        val worker = PingBatchWorker(call(), probe = { _, _ -> results.next() }, log = { lines += it })

        worker.answer(request(listOf(1, 2, 3), probe = setOf(1, 2, 3)))
        PingBatchWorker(call(), probe = { _, _ -> CertificateProbeResult.STOP_FAILED }, log = { lines += it })
            .answer(request(listOf(4), probe = setOf(4)))

        lines.forEach { it shouldNotContain "cfg-" }
        lines.toSet() shouldBe
            setOf(
                "cert probe: untrusted",
                "cert probe: not a certificate failure",
                "cert probe: did not run",
                "cert probe: stop failed; :ping retires after this batch",
            )
    }

    // ── :bg marks who may be probed ──────────────────────────────────────────

    private val fiveSeconds = LatencyOptions(PingMode.PROXY_HEAD, timeoutSeconds = 5, checkUrl = url)
    private val hysteria2: Outbound = Hysteria2Outbound("hy.example.com", 443, "pw", Hysteria2Tls("hy.example.com"))
    private val vless: Outbound =
        VlessOutbound(
            "198.51.100.7",
            443,
            "11111111-2222-3333-4444-555555555555",
            null,
            StreamSettings("tcp", Security.Tls("example.com", "chrome", false)),
        )

    @Test
    fun onlyAUdpServerIsMarkedForTheProbe() =
        runTest {
            val hy = planLatency(fiveSeconds, { false }, null, hysteria2) { "hy" }
            val vl = planLatency(fiveSeconds, { false }, null, vless) { "vl" }

            hy.shouldBeInstanceOf<LatencyPlan.Batched>()
            vl.shouldBeInstanceOf<LatencyPlan.Batched>()
            hy.certProbe shouldBe true
            vl.certProbe shouldBe false
        }

    @Test
    fun theMarkTravelsFromThePlanIntoTheBatch() =
        runTest {
            val sent = mutableListOf<PingTarget>()
            val runner =
                LatencyRunner<Long>(
                    measure = { _, _ -> error("not in place") },
                    loadProfile = { it },
                    scope = this,
                    plan = { id, _ -> LatencyPlan.Batched("cfg-$id", PingGroup(5, url), certProbe = id == 2L) },
                    measureBatch = { _, targets ->
                        sent += targets
                        emptyMap()
                    },
                )

            runner.start(1L, longArrayOf(1, 2), { fiveSeconds }, { _, _, _ -> }, {})
            advanceUntilIdle()

            sent.map { it.certProbe } shouldContainExactly listOf(false, true)
            PingBatchParcel.of(sent, 5, url).targets()?.map { it.certProbe } shouldBe listOf(false, true)
        }

    @Test
    fun markedServersTravelInTheirOwnBatchesAfterTheUnmarkedOnes() =
        runTest {
            val sent = mutableListOf<List<Long>>()
            val runner =
                LatencyRunner<Long>(
                    measure = { _, _ -> error("not in place") },
                    loadProfile = { it },
                    scope = this,
                    // Odd ids are Hysteria2-like (marked); listed first so order is not an accident.
                    plan = { id, _ -> LatencyPlan.Batched("cfg-$id", PingGroup(5, url), certProbe = id % 2 == 1L) },
                    measureBatch = { _, targets ->
                        sent += targets.map { it.key }
                        emptyMap()
                    },
                    concurrency = 1,
                )

            runner.start(1L, longArrayOf(1, 3, 2, 4, 6), { fiveSeconds }, { _, _, _ -> }, {})
            advanceUntilIdle()

            // Keys are run indices: ids 2, 4, 6 sit at indices 2, 3, 4; ids 1, 3 at 0, 1.
            sent shouldContainExactly listOf(listOf(2L, 3L, 4L), listOf(0L, 1L))
        }

    // ── The outcome across the boundary ──────────────────────────────────────

    @Test
    fun certUntrustedIsAppendedLastSoNoOrdinalMoves() {
        LatencyOutcome.entries.last() shouldBe LatencyOutcome.CERT_UNTRUSTED
        LatencyOutcome.FOREIGN_VPN.ordinal shouldBe 5
    }

    @Test
    fun certUntrustedSurvivesTheResultParcelsMapping() {
        val untrusted = LatencyResult.failed(LatencyOutcome.CERT_UNTRUSTED)
        val reply = PingBatchResultParcel.from(longArrayOf(7), mapOf(7L to untrusted))

        reply.outcomes.toList() shouldContainExactly listOf(LatencyOutcome.CERT_UNTRUSTED.ordinal)
        reply.toResults() shouldBe mapOf(7L to LatencyResult.failed(LatencyOutcome.CERT_UNTRUSTED))
    }

    @Test
    fun aRequestWithoutMarksProbesNothing() {
        // The old parcel shape: no flags array. A missing flag is "not eligible".
        PingBatchParcel(longArrayOf(1, 2), arrayOf("cfg-1", "cfg-2"), 5, url).targets()?.map { it.certProbe } shouldBe
            listOf(false, false)
    }
}
