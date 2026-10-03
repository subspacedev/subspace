// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.ThroughputWindow

class ThroughputRingTest {
    private val b = ThroughputWindow.BUCKET_MILLIS
    private val n = ThroughputWindow.BUCKET_COUNT

    private fun zeros(count: Int) = List(count) { 0L }

    @Test
    fun `a ring nothing was recorded in is 28 zeros`() {
        ThroughputRing().snapshot(nowMillis = 5_000) shouldBe zeros(n)
    }

    @Test
    fun `bytes land in the newest bucket, which is last`() {
        val ring = ThroughputRing()
        ring.record(nowMillis = 1_000, bytes = 100)
        ring.record(nowMillis = 2_000, bytes = 50)
        ring.snapshot(nowMillis = 2_000) shouldBe zeros(n - 1) + 150L
    }

    @Test
    fun `buckets roll over on the session clock, oldest first`() {
        val ring = ThroughputRing()
        ring.record(nowMillis = 0, bytes = 100)
        ring.record(nowMillis = b, bytes = 50)
        ring.record(nowMillis = 2 * b + 10, bytes = 7)
        ring.snapshot(nowMillis = 2 * b + 10) shouldBe zeros(n - 3) + listOf(100L, 50L, 7L)
    }

    @Test
    fun `buckets are anchored to the first record, not to the epoch`() {
        val ring = ThroughputRing()
        val start = 1_000_000L
        ring.record(nowMillis = start, bytes = 1)
        ring.record(nowMillis = start + b - 1, bytes = 2)
        ring.snapshot(nowMillis = start + b - 1) shouldBe zeros(n - 1) + 3L
    }

    @Test
    fun `a bucket is dropped once it is older than the window`() {
        val ring = ThroughputRing()
        ring.record(nowMillis = 0, bytes = 100)
        ring.snapshot(nowMillis = (n - 1) * b).first() shouldBe 100L
        ring.snapshot(nowMillis = n * b) shouldBe zeros(n)
    }

    @Test
    fun `silence longer than the window reads as empty, not as stale bars`() {
        val ring = ThroughputRing()
        ring.record(nowMillis = 0, bytes = 100)
        ring.record(nowMillis = b, bytes = 100)
        ring.record(nowMillis = 40 * b, bytes = 9)
        ring.snapshot(nowMillis = 40 * b) shouldBe zeros(n - 1) + 9L
    }

    @Test
    fun `quiet buckets between records are zero`() {
        val ring = ThroughputRing()
        ring.record(nowMillis = 0, bytes = 10)
        ring.record(nowMillis = 3 * b, bytes = 20)
        ring.snapshot(nowMillis = 3 * b) shouldBe zeros(n - 4) + listOf(10L, 0L, 0L, 20L)
    }

    @Test
    fun `a negative byte count is ignored, never subtracted`() {
        val ring = ThroughputRing()
        ring.record(nowMillis = 0, bytes = 10)
        ring.record(nowMillis = 1, bytes = -5)
        ring.snapshot(nowMillis = 1).last() shouldBe 10L
    }

    @Test
    fun `a clock that steps backwards keeps counting into the newest bucket`() {
        val ring = ThroughputRing()
        ring.record(nowMillis = 2 * b, bytes = 10)
        ring.record(nowMillis = b, bytes = 5)
        ring.snapshot(nowMillis = b).last() shouldBe 15L
    }

    @Test
    fun `a 32-bit-sized bucket keeps its full value`() {
        val ring = ThroughputRing()
        ring.record(nowMillis = 0, bytes = 5_000_000_000L)
        ring.snapshot(nowMillis = 0).last() shouldBe 5_000_000_000L
    }
}
