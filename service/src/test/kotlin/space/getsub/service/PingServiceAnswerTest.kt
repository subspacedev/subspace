// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.xray.PingBatchCall
import space.getsub.core.xray.XrayException

/** What `:ping` answers for one request, with the native call faked. */
class PingServiceAnswerTest {
    private val secret = """{"outbounds":[{"tag":"proxy","settings":{"id":"11111111-2222-3333-4444-555555555555"}}]}"""
    private val request = PingBatchParcel(longArrayOf(4, 9), arrayOf(secret, secret), 5, "https://x.invalid")

    @Test
    fun perItemResultsAreReturnedUnderTheRequestsKeys() {
        val reply =
            answerPingBatch(
                request,
                PingBatchCall {
                    """{"results":[{"success":true,"delay":120},{"success":false,"delay":11000,"error":"timeout"}]}"""
                },
            )

        reply.keys.toList() shouldContainExactly listOf(4L, 9L)
        reply.toResults() shouldBe
            mapOf(4L to LatencyResult.ok(120), 9L to LatencyResult.failed(LatencyOutcome.UNREACHABLE))
        // The sentinel never crosses back as a delay.
        reply.delays.toList() shouldContainExactly listOf(120, 0)
    }

    @Test
    fun aWholeCallFailureFailsEveryServer() {
        val reply = answerPingBatch(request, PingBatchCall { throw XrayException("bad $secret") })

        reply.toResults().values.map { it.outcome }.distinct() shouldContainExactly listOf(LatencyOutcome.UNREACHABLE)
    }

    @Test
    fun anUnexpectedThrowFailsEveryServerAndReportsTheClassNameOnly() {
        val reported = mutableListOf<String>()
        val reply = answerPingBatch(request, PingBatchCall { throw IllegalStateException(secret) }) { reported += it }

        reply.toResults().values.map { it.outcome }.distinct() shouldContainExactly listOf(LatencyOutcome.UNREACHABLE)
        reported shouldContainExactly listOf("IllegalStateException")
    }

    @Test
    fun aMalformedRequestNeverReachesTheCore() {
        var called = false
        val call =
            PingBatchCall {
                called = true
                null
            }

        val mismatched = PingBatchParcel(longArrayOf(1, 2), arrayOf(secret), 5, "https://x.invalid")
        answerPingBatch(mismatched, call).toResults().values.map { it.outcome }.distinct() shouldContainExactly
            listOf(LatencyOutcome.UNREACHABLE)
        val tooMany = PingBatchParcel(LongArray(6) { it.toLong() }, Array(6) { secret }, 5, "https://x.invalid")
        answerPingBatch(tooMany, call).keys.size shouldBe 6
        called shouldBe false
    }

    @Test
    fun theRequestParcelNeverPrintsAConfig() {
        request.toString() shouldNotContain "11111111"
        request.toString() shouldNotContain "outbounds"
    }
}
