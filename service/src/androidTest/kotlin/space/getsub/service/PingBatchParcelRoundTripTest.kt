// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.xray.PingTarget

/**
 * The `:bg` ↔ `:ping` parcels through a real [Parcel] (M9-D1 spec §2): the certificate-probe marks
 * written after the older fields, and [LatencyOutcome.CERT_UNTRUSTED] coming back. A JVM test cannot
 * do this — `Parcel` is a throwing stub there and this project has no Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class PingBatchParcelRoundTripTest {
    private fun <T> marshal(
        write: (Parcel) -> Unit,
        read: (Parcel) -> T,
    ): T {
        val parcel = Parcel.obtain()
        try {
            write(parcel)
            parcel.setDataPosition(0)
            return read(parcel)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun theRequestKeepsEveryFieldAndTheProbeMarksInOrder() {
        val sent =
            PingBatchParcel.of(
                listOf(PingTarget(4, "cfg-4", certProbe = false), PingTarget(9, "cfg-9", certProbe = true)),
                timeoutSeconds = 7,
                url = "https://x.invalid/204",
            )

        val got = marshal({ sent.writeToParcel(it, 0) }, { PingBatchParcel.CREATOR.createFromParcel(it) })

        got.keys.toList() shouldBe listOf(4L, 9L)
        got.configs.toList() shouldBe listOf("cfg-4", "cfg-9")
        got.timeoutSeconds shouldBe 7
        got.url shouldBe "https://x.invalid/204"
        got.targets() shouldBe sent.targets()
        got.targets()?.map { it.certProbe } shouldBe listOf(false, true)
    }

    @Test
    fun aRequestWrittenWithoutMarksReadsAsProbeNothing() {
        // The pre-M9-D1 layout: the four older fields only.
        val got =
            marshal(
                {
                    it.writeLongArray(longArrayOf(1))
                    it.writeStringArray(arrayOf("cfg-1"))
                    it.writeInt(5)
                    it.writeString("https://x.invalid")
                },
                { PingBatchParcel.CREATOR.createFromParcel(it) },
            )

        got.targets()?.map { it.certProbe } shouldBe listOf(false)
        got.timeoutSeconds shouldBe 5
    }

    @Test
    fun certUntrustedComesBackAsItself() {
        val sent =
            PingBatchResultParcel.from(
                longArrayOf(1, 2),
                mapOf(1L to LatencyResult.failed(LatencyOutcome.CERT_UNTRUSTED), 2L to LatencyResult.ok(80)),
            )

        val got = marshal({ sent.writeToParcel(it, 0) }, { PingBatchResultParcel.CREATOR.createFromParcel(it) })

        got.toResults() shouldBe
            mapOf(1L to LatencyResult.failed(LatencyOutcome.CERT_UNTRUSTED), 2L to LatencyResult.ok(80))
    }
}
