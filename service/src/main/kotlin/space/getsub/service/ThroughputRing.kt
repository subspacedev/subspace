// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.core.model.ThroughputWindow

/**
 * The last hour of throughput, as [bucketCount] totals (M8.6 spec §4.1.1).
 *
 * Pure: it owns no timer and reads no clock. [TrafficSamplerLoop] passes the
 * time in and feeds it the per-tick byte deltas its own [TrafficSampler]
 * already produced, so the 32-bit wrap and retained-TUN restart rules
 * (M8.5 spec §1.3, §1.4) are applied once, there, and never re-derived here.
 *
 * Buckets are anchored to the first [record], which is the session's first
 * sample. One ring lives exactly as long as one `TrafficSampler`, so a
 * restart that retains the TUN continues the same ring, and a new session
 * starts a new one.
 *
 * Every call advances the ring to `nowMillis` first, zeroing the buckets the
 * clock has passed. Reading after a long silence therefore shows the silence,
 * never bars from more than one window ago.
 *
 * **Not thread-safe**, like [TrafficSampler]: driven from the loop's single
 * coroutine.
 */
internal class ThroughputRing(
    private val bucketCount: Int = ThroughputWindow.BUCKET_COUNT,
    private val bucketMillis: Long = ThroughputWindow.BUCKET_MILLIS,
) {
    private val buckets = LongArray(bucketCount)
    private var startMillis: Long? = null

    /** Absolute index (buckets since [startMillis]) of the newest bucket. */
    private var newest = -1L

    fun record(
        nowMillis: Long,
        bytes: Long,
    ) {
        val index = advanceTo(nowMillis)
        buckets[slot(index)] += bytes.coerceAtLeast(0L)
    }

    /** [bucketCount] totals, oldest first, ending with the bucket `nowMillis` falls in. */
    fun snapshot(nowMillis: Long): List<Long> {
        if (startMillis == null) return List(bucketCount) { 0L }
        val index = advanceTo(nowMillis)
        return (index - bucketCount + 1..index).map { i -> if (i < 0) 0L else buckets[slot(i)] }
    }

    /**
     * Moves the newest bucket to the one `nowMillis` falls in, zeroing every
     * bucket passed on the way. A clock reading before the newest bucket
     * (a clock that stepped back) stays in the newest bucket.
     */
    private fun advanceTo(nowMillis: Long): Long {
        val start = startMillis ?: nowMillis.also { startMillis = it }
        val index = maxOf(newest, (nowMillis - start).coerceAtLeast(0L) / bucketMillis)
        val firstToClear = maxOf(newest + 1, index - bucketCount + 1)
        for (i in firstToClear..index) buckets[slot(i)] = 0L
        newest = index
        return index
    }

    private fun slot(index: Long): Int = (index % bucketCount).toInt()
}
