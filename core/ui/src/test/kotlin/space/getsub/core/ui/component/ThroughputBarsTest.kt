// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import io.kotest.matchers.shouldBe
import org.junit.Test

class ThroughputBarsTest {
    @Test
    fun theLargestBucketIsFullHeightAndOthersScale() {
        throughputBars(listOf(0L, 50L, 100L), maxHeightDp = 56, minHeightDp = 3) shouldBe listOf(3, 28, 56)
    }

    /** No traffic at all must not divide by zero; every bar sits at the floor. */
    @Test
    fun allZeroBucketsAreAllAtTheFloor() {
        throughputBars(List(28) { 0L }) shouldBe List(28) { 3 }
    }

    @Test
    fun peakIsTheLargestBucketPerSecond() {
        peakBytesPerSecond(listOf(1_000L, 257_200L, 0L), bucketMillis = 128_600L) shouldBe 2_000L
    }

    @Test
    fun anEmptyHistoryHasNoPeak() {
        peakBytesPerSecond(emptyList(), bucketMillis = 128_600L) shouldBe 0L
    }
}
