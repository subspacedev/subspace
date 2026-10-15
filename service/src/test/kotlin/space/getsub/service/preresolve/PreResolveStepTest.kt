// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service.preresolve

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import org.junit.Test
import space.getsub.core.data.preresolve.PreResolveDecision
import space.getsub.core.data.preresolve.PreResolveSource
import space.getsub.core.network.preresolve.PreResolution
import space.getsub.core.network.preresolve.PreResolveReason
import space.getsub.core.network.preresolve.ResolverConfig
import space.getsub.core.xray.ServerAddressPin

class PreResolveStepTest {
    private val run =
        PreResolveDecision.Run(
            ResolverConfig("https://dns.example/q", "1.1.1.1", null),
            PreResolveSource.User,
        )
    private val logged = mutableListOf<String>()
    private var resolveCalls = 0

    private fun step(
        decision: PreResolveDecision,
        result: PreResolution,
    ) = PreResolveStep(
        decide = { decision },
        resolve = { _, _, _ ->
            resolveCalls++
            result
        },
        log = { logged += it },
    )

    private val matchingRaw = """{"outbounds":[{"settings":{"vnext":[{"address":"srv.example"}]}}]}"""
    private val otherRaw = """{"outbounds":[{"settings":{"vnext":[{"address":"other.example"}]}}]}"""

    @Test fun offIsNullAndSilent() =
        runTest {
            step(PreResolveDecision.Off(PreResolveReason.Off), PreResolution.Skipped(PreResolveReason.Off))
                .run(1, "srv.example", 443, null) shouldBe null
            logged shouldBe emptyList()
            resolveCalls shouldBe 0
        }

    @Test fun resolvedGivesAPin() =
        runTest {
            step(run, PreResolution.Resolved("203.0.113.5", 3, 40)).run(1, "srv.example", 443, null) shouldBe
                ServerAddressPin("srv.example", "203.0.113.5")
            logged.single() shouldContain "outcome=Resolved"
        }

    @Test fun resolvedForAMatchingRawConfigGivesAPin() =
        runTest {
            step(run, PreResolution.Resolved("203.0.113.5", 1, 20)).run(1, "srv.example", 443, matchingRaw) shouldBe
                ServerAddressPin("srv.example", "203.0.113.5")
        }

    @Test fun failedGivesNullAndLogs() =
        runTest {
            step(run, PreResolution.Failed(PreResolveReason.DohTimeout, 0, 3000))
                .run(1, "srv.example", 443, null) shouldBe null
            logged.single() shouldContain "reason=DohTimeout"
        }

    @Test fun rawConfigWithoutTheDomainIsSkippedBeforeDoh() =
        runTest {
            step(run, PreResolution.Resolved("203.0.113.5", 1, 20)).run(1, "srv.example", 443, otherRaw) shouldBe null
            logged.single() shouldContain "reason=ConfigNotRecognised"
            resolveCalls shouldBe 0
        }

    @Test fun invalidUserResolverLogsOnce() =
        runTest {
            step(
                PreResolveDecision.Off(PreResolveReason.InvalidUserResolver),
                PreResolution.Skipped(PreResolveReason.Off),
            ).run(1, "srv.example", 443, null) shouldBe null
            logged.single() shouldContain "reason=InvalidUserResolver"
        }

    @Test fun alreadyAnIpIsSilent() =
        runTest {
            step(run, PreResolution.Skipped(PreResolveReason.AlreadyIp)).run(1, "203.0.113.5", 443, null) shouldBe null
            logged shouldBe emptyList()
        }

    @Test fun logLinesNeverCarryHostsOrIps() =
        runTest {
            listOf(
                PreResolution.Resolved("203.0.113.5", 3, 40),
                PreResolution.Failed(PreResolveReason.NoneReachable, 3, 1500),
                PreResolution.Failed(PreResolveReason.DohError, 0, 12),
            ).forEach { step(run, it).run(1, "srv.example", 443, null) }
            step(run, PreResolution.Resolved("203.0.113.5", 1, 1)).run(1, "srv.example", 443, otherRaw)
            logged.size shouldBe 4
            logged.forEach { line ->
                listOf("srv.example", "203.0.113.5", "dns.example", "1.1.1.1").forEach { secret ->
                    line shouldNotContain secret
                }
            }
        }
}
