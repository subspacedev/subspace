// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.preresolve

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import org.junit.Test
import space.getsub.core.network.preresolve.PreResolution
import space.getsub.core.network.preresolve.PreResolveReason
import space.getsub.core.network.preresolve.ResolverConfig

/** [runPreResolution], the orchestration behind [ServerPreResolution.run] (M9-C spec §3.1). */
class PreResolutionRunTest {
    private val run =
        PreResolveDecision.Run(
            ResolverConfig("https://dns.example/q", "1.1.1.1", null),
            PreResolveSource.User,
        )
    private val logged = mutableListOf<String>()
    private var resolveCalls = 0
    private val raceFlags = mutableListOf<Boolean>()

    // Stands in for :core:xray's AddressPinInjector.matches, which this module must not depend on.
    private val matcherCalls = mutableListOf<Pair<String, String>>()
    private val namesHost: (String, String) -> Boolean = { raw, host ->
        matcherCalls += raw to host
        raw.contains("\"address\":\"$host\"")
    }

    private val matchingRaw = """{"outbounds":[{"settings":{"vnext":[{"address":"srv.example"}]}}]}"""
    private val otherRaw = """{"outbounds":[{"settings":{"vnext":[{"address":"other.example"}]}}]}"""

    private suspend fun step(
        decision: PreResolveDecision,
        result: PreResolution,
        host: String = "srv.example",
        raw: String? = null,
        udpServer: Boolean = false,
    ): String? {
        val outcome =
            runPreResolution(
                rowId = 1,
                host = host,
                port = 443,
                rawJsonRunsAsWritten = raw,
                configNamesHost = namesHost,
                decide = { decision },
                udpServer = udpServer,
                resolve = { _, _, _, race ->
                    raceFlags += race
                    resolveCalls++
                    result
                },
            )
        outcome.logLine?.let { logged += it }
        return outcome.ip
    }

    @Test fun offIsNullAndSilent() =
        runTest {
            val off = PreResolveDecision.Off(PreResolveReason.Off)
            step(off, PreResolution.Skipped(PreResolveReason.Off)) shouldBe null
            logged shouldBe emptyList()
            resolveCalls shouldBe 0
        }

    @Test fun resolvedGivesAPin() =
        runTest {
            step(run, PreResolution.Resolved("203.0.113.5", 3, 40)) shouldBe "203.0.113.5"
            logged.single() shouldContain "outcome=Resolved"
        }

    @Test fun resolvedForAMatchingRawConfigGivesAPin() =
        runTest {
            step(run, PreResolution.Resolved("203.0.113.5", 1, 20), raw = matchingRaw) shouldBe "203.0.113.5"
            matcherCalls.single() shouldBe (matchingRaw to "srv.example")
        }

    @Test fun failedGivesNullAndLogs() =
        runTest {
            step(run, PreResolution.Failed(PreResolveReason.DohTimeout, 0, 3000)) shouldBe null
            logged.single() shouldContain "reason=DohTimeout"
        }

    @Test fun rawConfigWithoutTheDomainIsSkippedBeforeDoh() =
        runTest {
            step(run, PreResolution.Resolved("203.0.113.5", 1, 20), raw = otherRaw) shouldBe null
            logged.single() shouldContain "reason=ConfigNotRecognised"
            resolveCalls shouldBe 0
        }

    @Test fun invalidUserResolverLogsOnce() =
        runTest {
            step(
                PreResolveDecision.Off(PreResolveReason.InvalidUserResolver, PreResolveSource.User),
                PreResolution.Skipped(PreResolveReason.Off),
            ) shouldBe null
            logged.single() shouldBe "preresolve outcome=Skipped source=User reason=InvalidUserResolver"
        }

    @Test fun alreadyAnIpIsSilent() =
        runTest {
            step(run, PreResolution.Skipped(PreResolveReason.AlreadyIp), host = "203.0.113.5") shouldBe null
            logged shouldBe emptyList()
        }

    @Test fun logLinesNeverCarryHostsOrIps() =
        runTest {
            listOf(
                PreResolution.Resolved("203.0.113.5", 3, 40),
                PreResolution.Failed(PreResolveReason.NoneReachable, 3, 1500),
                PreResolution.Failed(PreResolveReason.DohError, 0, 12),
            ).forEach { step(run, it) }
            step(run, PreResolution.Resolved("203.0.113.5", 1, 1), raw = otherRaw)
            logged.size shouldBe 4
            logged.forEach { line ->
                listOf("srv.example", "203.0.113.5", "dns.example", "1.1.1.1").forEach { secret ->
                    line shouldNotContain secret
                }
            }
        }

    @Test fun hysteria2PreResolvesToTheFirstAnswerWithoutRacing() =
        runTest {
            step(run, PreResolution.Resolved("203.0.113.5", 3, 40, raced = false), udpServer = true) shouldBe
                "203.0.113.5"
            raceFlags shouldBe listOf(false)
            logged.single() shouldContain "outcome=Resolved"
            logged.single() shouldContain "reason=first answer, UDP server, not raced"
            logged.single() shouldNotContain "203.0.113.5"
        }

    @Test fun aRawHysteria2ConfigStillGoesThroughTheHostMatcherThenSkipsTheRace() =
        runTest {
            val resolved = PreResolution.Resolved("203.0.113.5", 1, 20, raced = false)
            step(run, resolved, raw = matchingRaw, udpServer = true) shouldBe "203.0.113.5"
            matcherCalls.single() shouldBe (matchingRaw to "srv.example")
            raceFlags shouldBe listOf(false)
        }

    @Test fun otherProtocolsStillRace() =
        runTest {
            step(run, PreResolution.Resolved("203.0.113.5", 3, 40))
            raceFlags shouldBe listOf(true)
            logged.single() shouldNotContain "not raced"
        }

    @Test fun outcomeToStringRedactsTheIp() {
        PreResolveOutcome("203.0.113.5", "preresolve outcome=Resolved").toString() shouldNotContain "203.0.113.5"
    }
}
