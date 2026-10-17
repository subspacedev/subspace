// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.Profile
import space.getsub.core.model.ShadowsocksOutbound

class PingBatchTest {
    private val a = PingTarget(1L, """{"outbounds":[{"tag":"proxy","secret":"UUID-A"}]}""")
    private val b = PingTarget(2L, """{"outbounds":[{"tag":"proxy","secret":"UUID-B"}]}""")
    private val failed = LatencyResult.failed(LatencyOutcome.UNREACHABLE)

    @Test
    fun `requestCarriesTimeoutSecondsUrlAndProxyTag`() {
        val req = Json.parseToJsonElement(PingBatch.request(listOf(a, b), 7, "https://x.test/204")).jsonObject
        req.getValue("timeout").jsonPrimitive.int shouldBe 7
        req.getValue("url").jsonPrimitive.content shouldBe "https://x.test/204"
        val configs = req.getValue("configs").jsonArray
        configs.size shouldBe 2
        configs.forEach { it.jsonObject.getValue("outboundTag").jsonPrimitive.content shouldBe "proxy" }
        configs[0].jsonObject.getValue("xrayJson").jsonPrimitive.content shouldBe a.xrayJson
    }

    @Test
    fun `moreThanFiveTargetsIsRejected`() {
        val six = List(6) { PingTarget(it.toLong(), "{}") }
        assertThrows(IllegalArgumentException::class.java) { PingBatch.request(six, 5, "https://x.test") }
        PingBatch.request(six.take(5), 5, "https://x.test")
    }

    @Test
    fun `sentinelDelaysNeverBecomeNumbers`() {
        val data = """{"results":[{"success":false,"delay":10000,"error":"e"},{"success":false,"delay":11000}]}"""
        PingBatch.results(listOf(a, b), data) shouldBe mapOf(1L to failed, 2L to failed)
    }

    @Test
    fun `successMapsToMillis`() {
        val data = """{"results":[{"success":true,"delay":231},{"success":false,"delay":5}]}"""
        PingBatch.results(listOf(a, b), data) shouldBe mapOf(1L to LatencyResult.ok(231), 2L to failed)
    }

    @Test
    fun `shortResultsArrayFailsTheRest`() {
        PingBatch.results(listOf(a, b), """{"results":[{"success":true,"delay":10}]}""") shouldBe
            mapOf(1L to LatencyResult.ok(10), 2L to failed)
    }

    @Test
    fun `missingOrMalformedDataFailsEveryTarget`() {
        val all = mapOf(1L to failed, 2L to failed)
        PingBatch.results(listOf(a, b), null) shouldBe all
        PingBatch.results(listOf(a, b), "{}") shouldBe all
        PingBatch.results(listOf(a, b), "not json") shouldBe all
        PingBatch.results(listOf(a, b), """{"results":[null,{"success":"yes"}]}""") shouldBe all
    }

    @Test
    fun `wholeCallFailureFailsEveryTargetWithoutLeakingTheMessage`() {
        val call = PingBatchCall { throw XrayException("libXray pingBatch failed: UUID-A") }
        val out = PingBatch.measure(call, listOf(a, b), 5, "https://x.test")
        out shouldBe mapOf(1L to failed, 2L to failed)
        out.toString() shouldNotContain "UUID"
    }

    @Test
    fun `measureReturnsParsedResults`() {
        val call = PingBatchCall { """{"results":[{"success":true,"delay":42}]}""" }
        PingBatch.measure(call, listOf(a), 5, "https://x.test") shouldBe mapOf(1L to LatencyResult.ok(42))
    }

    @Test
    fun `targetToStringHidesTheConfig`() {
        a.toString() shouldNotContain "UUID-A"
        a.toString() shouldNotContain "outbounds"
    }

    @Test
    fun `pingConfigHasAProxyOutboundAndNullsForUnsupportedConfigs`() {
        val json = pingConfig(GOLDEN_PROFILE, null)!!
        Json.parseToJsonElement(json).jsonObject.getValue("outbounds").jsonArray.any {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "proxy"
        } shouldBe true
        // M9-D2a: every protocol generates now; a cipher v26.10.10 dropped is what still refuses.
        val refused = ShadowsocksOutbound(address = "example.com", port = 8388, method = "aes-192-gcm", password = "pw")
        pingConfig(Profile(id = "p", name = "t", outbound = refused), null) shouldBe null
    }
}
