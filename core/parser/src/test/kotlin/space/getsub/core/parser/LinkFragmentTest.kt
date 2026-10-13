// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.Checked
import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.ObfuscationProblem
import space.getsub.core.model.XrayRange

class LinkFragmentTest {
    @Test
    fun happsOrderIsLengthIntervalPackets() {
        LinkFragment.parse("80-250,10-100,tlshello") shouldBe
            Checked.Ok(FragmentSpec(FragmentPackets.TlsHello, XrayRange(80, 250), XrayRange(10, 100), null))
    }

    @Test
    fun aRangeOfPacketsIsAccepted() {
        val parsed = LinkFragment.parse(" 50 , 5 , 1-3 ") as Checked.Ok
        parsed.value.packets shouldBe FragmentPackets.Records(XrayRange(1, 3))
    }

    @Test
    fun theWrongNumberOfPartsIsNotARange() {
        LinkFragment.parse("80-250,tlshello") shouldBe Checked.Invalid(ObfuscationProblem.NotARange)
    }

    @Test
    fun aZeroLengthIsRefusedLikeTheSettingsPage() {
        LinkFragment.parse("0-5,10,tlshello") shouldBe Checked.Invalid(ObfuscationProblem.LengthFromZero)
    }

    @Test
    fun anOverCapValueIsRefused() {
        LinkFragment.parse("1-2147483647,10,tlshello") shouldBe Checked.Invalid(ObfuscationProblem.OutOfBounds)
        LinkFragment.parse("80-250,2000000000,tlshello") shouldBe Checked.Invalid(ObfuscationProblem.OutOfBounds)
    }
}
