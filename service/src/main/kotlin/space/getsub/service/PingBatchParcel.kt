// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.os.Parcel
import android.os.Parcelable
import space.getsub.core.xray.PingTarget

/**
 * One `pingBatch` request, from `:bg` to `:ping`.
 *
 * [configs] are whole generated Xray configs — address, UUID, keys. A plain class
 * with a hand-written [toString], as [ProfileParcel] is: a `data class` would
 * print every secret wherever the parcel is logged (ARCHITECTURE.md §5.6).
 * [keys] and [configs] are parallel; [targets] re-pairs them.
 */
public class PingBatchParcel(
    public val keys: LongArray,
    public val configs: Array<String>,
    public val timeoutSeconds: Int,
    public val url: String,
) : Parcelable {
    internal constructor(parcel: Parcel) : this(
        keys = parcel.createLongArray() ?: LongArray(0),
        configs = parcel.createStringArray()?.map { it.orEmpty() }?.toTypedArray() ?: emptyArray(),
        timeoutSeconds = parcel.readInt(),
        url = parcel.readString().orEmpty(),
    )

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeLongArray(keys)
        dest.writeStringArray(configs)
        dest.writeInt(timeoutSeconds)
        dest.writeString(url)
    }

    override fun describeContents(): Int = 0

    /** The batch, or null when the parallel arrays disagree (a malformed request). */
    internal fun targets(): List<PingTarget>? =
        if (keys.size != configs.size) null else keys.indices.map { PingTarget(keys[it], configs[it]) }

    override fun toString(): String =
        "PingBatchParcel(servers=${keys.size}, configs=<redacted>, timeoutSeconds=$timeoutSeconds)"

    public companion object {
        internal fun of(
            batch: List<PingTarget>,
            timeoutSeconds: Int,
            url: String,
        ): PingBatchParcel =
            PingBatchParcel(
                keys = LongArray(batch.size) { batch[it].key },
                configs = Array(batch.size) { batch[it].xrayJson },
                timeoutSeconds = timeoutSeconds,
                url = url,
            )

        @JvmField
        public val CREATOR: Parcelable.Creator<PingBatchParcel> =
            object : Parcelable.Creator<PingBatchParcel> {
                override fun createFromParcel(source: Parcel): PingBatchParcel = PingBatchParcel(source)

                override fun newArray(size: Int): Array<PingBatchParcel?> = arrayOfNulls(size)
            }
    }
}
