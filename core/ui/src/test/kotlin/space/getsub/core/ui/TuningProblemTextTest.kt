// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.TuningProblem

/** M9-E spec §3, §4, §7.1: one wording for why a tuning value is not applied, on every screen. */
class TuningProblemTextTest {
    private val every =
        listOf(
            TuningProblem.OutOfRange(1280, 65_535, "xray-tun-mtu"),
            TuningProblem.BadValue("mux-quic"),
            TuningProblem.RangeTooWide(0),
            TuningProblem.RangeCoversDns(0),
            TuningProblem.NeedsConsent,
            TuningProblem.Declined,
        )

    @Test
    fun `every problem has its own reason`() {
        every.map { it.reason().res }.toSet() shouldHaveSize every.size
    }

    @Test
    fun `a range is stated with its bounds`() {
        TuningProblem.OutOfRange(1280, 65_535, "xray-tun-mtu").reason() shouldBe
            TuningText(R.string.tuning_reason_out_of_range, listOf(1280, 65_535))
        TuningProblem.BadValue("mux-quic").reason() shouldBe TuningText(R.string.tuning_reason_bad_value)
    }

    @Test
    fun `consent problems read as not applied, a dropped part as partly ignored, the rest as ignored`() {
        tuningIgnoreKind(TuningProblem.NeedsConsent, partial = false) shouldBe TuningIgnoreKind.NotApplied
        tuningIgnoreKind(TuningProblem.Declined, partial = false) shouldBe TuningIgnoreKind.NotApplied
        tuningIgnoreKind(TuningProblem.RangeTooWide(0), partial = true) shouldBe TuningIgnoreKind.PartlyIgnored
        tuningIgnoreKind(TuningProblem.RangeTooWide(0), partial = false) shouldBe TuningIgnoreKind.Ignored
        tuningIgnoreKind(TuningProblem.OutOfRange(1, 2), partial = false) shouldBe TuningIgnoreKind.Ignored
    }

    @Test
    fun `each kind has its own directive line and subscription-value clause`() {
        TuningIgnoreKind.entries.map { it.directiveLineRes() }.toSet() shouldHaveSize TuningIgnoreKind.entries.size
        TuningIgnoreKind.entries.map { it.subscriptionValueRes() }.toSet() shouldHaveSize TuningIgnoreKind.entries.size
    }
}
