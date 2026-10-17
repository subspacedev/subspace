// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.Health
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.ThroughputWindow
import space.getsub.core.model.TrafficSample
import space.getsub.core.ui.component.Outcome

class HomeCardsTest {
    private val history = List(ThroughputWindow.BUCKET_COUNT) { it.toLong() }
    private val sample = TrafficSample(1, 2, 0, 0, throughput = history)
    private val connected = ConnectionState.Connected(0L, 1080, 0, Health.Open)

    @Test
    fun `a live session shows its history`() {
        HomeState(connection = connected, traffic = sample).throughputBuckets() shouldBe history
    }

    @Test
    fun `a cached sample outside Connected is not shown`() {
        val reconnecting = ConnectionState.Reconnecting(FailureReason.CoreStartFailed, 1, blocked = true)
        HomeState(connection = reconnecting, traffic = sample).throughputBuckets() shouldBe
            List(ThroughputWindow.BUCKET_COUNT) { 0L }
        HomeState(connection = reconnecting, traffic = sample).liveSample() shouldBe null
    }

    @Test
    fun `a malformed history is drawn flat, never truncated or padded`() {
        val malformed = sample.copy(throughput = listOf(1L, 2L))
        HomeState(connection = connected, traffic = malformed).throughputBuckets() shouldBe
            List(ThroughputWindow.BUCKET_COUNT) { 0L }
    }

    @Test
    fun `lightweight mode hides traffic in every state`() {
        HomeState(connection = connected, traffic = sample, trafficHidden = true).showsTraffic() shouldBe false
        HomeState(trafficHidden = false).showsTraffic() shouldBe true
    }

    @Test
    fun `only generated configs get an outcome tag`() {
        outcomeForTag("proxy", generatedConfig = true) shouldBe Outcome.Proxy
        outcomeForTag("direct", generatedConfig = true) shouldBe Outcome.Direct
        outcomeForTag("block", generatedConfig = true) shouldBe Outcome.Block
        outcomeForTag("my-relay", generatedConfig = true) shouldBe null
        outcomeForTag("proxy", generatedConfig = false) shouldBe null
    }

    @Test
    fun `the latency tile names each outcome`() {
        HomeState(isMeasuringLatency = true, latency = LatencyResult.ok(5)).latencyLabel() shouldBe LatencyLabel.Testing
        HomeState(latency = null).latencyLabel() shouldBe LatencyLabel.None
        HomeState(latency = LatencyResult.ok(42)).latencyLabel() shouldBe LatencyLabel.Ms(42)
        HomeState(latency = LatencyResult.failed(LatencyOutcome.UNSUPPORTED)).latencyLabel() shouldBe
            LatencyLabel.Unsupported
        HomeState(latency = LatencyResult.failed(LatencyOutcome.FOREIGN_VPN)).latencyLabel() shouldBe
            LatencyLabel.ForeignVpn
        HomeState(latency = LatencyResult.failed(LatencyOutcome.CERT_UNTRUSTED)).latencyLabel() shouldBe
            LatencyLabel.CertUntrusted
        HomeState(latency = LatencyResult.failed(LatencyOutcome.UNREACHABLE)).latencyLabel() shouldBe
            LatencyLabel.Failed
        HomeState(latency = LatencyResult.failed(LatencyOutcome.TIMEOUT)).latencyLabel() shouldBe LatencyLabel.Failed
    }
}
