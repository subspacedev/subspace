// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model.automation

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult

class FastestPickTest {
    private fun c(
        id: Long,
        name: String,
        index: Int = id.toInt(),
    ) = PickCandidate(id, name, index)

    private fun ok(ms: Int) = LatencyResult.ok(ms)

    @Test
    fun lowestOkWins() {
        val cands = listOf(c(1, "A"), c(2, "B"), c(3, "C"))
        pickFastest(cands, mapOf(1L to ok(80), 2L to ok(20), 3L to ok(50)), NetworkKind.Wifi, true, null) shouldBe 2L
    }

    @Test
    fun nonOkAndMissingAreSkipped() {
        val cands = listOf(c(1, "A"), c(2, "B"), c(3, "C"), c(4, "D"))
        val results =
            mapOf(
                1L to LatencyResult.failed(LatencyOutcome.TIMEOUT),
                2L to LatencyResult.failed(LatencyOutcome.FOREIGN_VPN),
                3L to ok(90),
            )
        pickFastest(cands, results, NetworkKind.Wifi, true, null) shouldBe 3L
    }

    @Test
    fun markersExcludeOnTheWrongNetwork() {
        val cands = listOf(c(1, "X LTE"), c(2, "Plain"))
        val results = mapOf(1L to ok(10), 2L to ok(100))
        pickFastest(cands, results, NetworkKind.Wifi, true, null) shouldBe 2L
    }

    @Test
    fun markersIgnoredWhenOff() {
        val cands = listOf(c(1, "X LTE"), c(2, "Plain"))
        val results = mapOf(1L to ok(10), 2L to ok(100))
        pickFastest(cands, results, NetworkKind.Wifi, false, null) shouldBe 1L
    }

    @Test
    fun otherNetworkExcludesNothing() {
        val cands = listOf(c(1, "X LTE"), c(2, "Plain"))
        val results = mapOf(1L to ok(10), 2L to ok(100))
        pickFastest(cands, results, NetworkKind.Other, true, null) shouldBe 1L
    }

    @Test
    fun tieGoesToActiveThenLowerListIndex() {
        val cands = listOf(c(1, "A", 5), c(2, "B", 3), c(3, "C", 9))
        val results = mapOf(1L to ok(30), 2L to ok(30), 3L to ok(30))
        pickFastest(cands, results, NetworkKind.Wifi, true, 3L) shouldBe 3L
        pickFastest(cands, results, NetworkKind.Wifi, true, null) shouldBe 2L
    }

    @Test
    fun nullWhenNothingQualifies() {
        val cands = listOf(c(1, "X LTE"), c(2, "Plain"))
        val results = mapOf(1L to ok(10), 2L to LatencyResult.failed(LatencyOutcome.UNREACHABLE))
        pickFastest(cands, results, NetworkKind.Wifi, true, null) shouldBe null
        pickFastest(emptyList(), emptyMap(), NetworkKind.Wifi, true, null) shouldBe null
    }

    @Test
    fun modesFallBack() {
        autoConnectModeFrom("bogus") shouldBe AutoConnectMode.LastPicked
        autoConnectModeFrom(null) shouldBe AutoConnectMode.LastPicked
        autoConnectModeFrom("fastest_overall") shouldBe AutoConnectMode.FastestOverall
        launchTestScopeFrom(null) shouldBe LaunchTestScope.AllServers
        launchTestScopeFrom("active_subscription") shouldBe LaunchTestScope.ActiveSubscription
    }

    @Test
    fun candidateToStringRedactsName() {
        c(1, "Secret Name").toString() shouldNotContain "Secret"
    }
}
