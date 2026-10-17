// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.os.Parcel
import android.os.Parcelable
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult

/**
 * One `pingBatch` answer, from `:ping` to `:bg`: parallel [keys], [delays] and
 * [outcomes] ([LatencyOutcome] ordinals, safe for the reason
 * `ILatencyCallback`'s are — nothing here outlives a bind).
 *
 * No error text crosses: libXray's per-item `error` can quote the config
 * (ARCHITECTURE.md §5.6). A delay is meaningful only beside [LatencyOutcome.OK];
 * [toResults] enforces that, so a `10000`/`11000` sentinel can never read as a
 * measurement on this side either (§10.1).
 */
public class PingBatchResultParcel(
    public val keys: LongArray,
    public val delays: IntArray,
    public val outcomes: IntArray,
) : Parcelable {
    internal constructor(parcel: Parcel) : this(
        keys = parcel.createLongArray() ?: LongArray(0),
        delays = parcel.createIntArray() ?: IntArray(0),
        outcomes = parcel.createIntArray() ?: IntArray(0),
    )

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeLongArray(keys)
        dest.writeIntArray(delays)
        dest.writeIntArray(outcomes)
    }

    override fun describeContents(): Int = 0

    /**
     * Results by key. An entry whose arrays are short, or whose outcome this side
     * cannot name, is a failure — a value we cannot read is never a number.
     */
    internal fun toResults(): Map<Long, LatencyResult> =
        keys.indices.associate { i ->
            val outcome = outcomes.getOrNull(i)?.let { LatencyOutcome.entries.getOrNull(it) }
            val delay = delays.getOrNull(i)
            keys[i] to
                if (outcome == LatencyOutcome.OK && delay != null && delay >= 0) {
                    LatencyResult.ok(delay)
                } else {
                    LatencyResult.failed(
                        outcome?.takeUnless { it == LatencyOutcome.OK } ?: LatencyOutcome.UNREACHABLE,
                    )
                }
        }

    override fun toString(): String = "PingBatchResultParcel(servers=${keys.size})"

    public companion object {
        /** [keys] in order, each looked up in [results]; a missing key fails. */
        internal fun from(
            keys: LongArray,
            results: Map<Long, LatencyResult>,
        ): PingBatchResultParcel {
            val ordered = keys.map { results[it] ?: LatencyResult.failed(LatencyOutcome.UNREACHABLE) }
            return PingBatchResultParcel(
                keys = keys.copyOf(),
                delays = IntArray(ordered.size) { ordered[it].delayMillis },
                outcomes = IntArray(ordered.size) { ordered[it].outcome.ordinal },
            )
        }

        @JvmField
        public val CREATOR: Parcelable.Creator<PingBatchResultParcel> =
            object : Parcelable.Creator<PingBatchResultParcel> {
                override fun createFromParcel(source: Parcel): PingBatchResultParcel = PingBatchResultParcel(source)

                override fun newArray(size: Int): Array<PingBatchResultParcel?> = arrayOfNulls(size)
            }
    }
}
