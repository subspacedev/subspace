// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.sync

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.ObfuscationValidation
import space.getsub.core.model.XrayRange
import space.getsub.core.network.FetchFailure
import space.getsub.core.parser.RedirectRejection

class FetchDecisionsTest {
    private val off = FragmentSettings.DEFAULT.copy(enabled = false)
    private val on = FragmentSettings.DEFAULT.copy(enabled = true, length = "60-90")

    @Test
    fun theUsersSwitchWinsOverTheLink() {
        FetchDecisions.fetchFragment(on, "80-250,10-100,tlshello") shouldBe ObfuscationValidation.fragmentOrDefault(on)
    }

    @Test
    fun theLinkAppliesWhenTheSwitchIsOff() {
        val spec = FetchDecisions.fetchFragment(off, "80-250,10-100,tlshello")!!
        spec.length shouldBe XrayRange(80, 250)
        spec.delay shouldBe XrayRange(10, 100)
        spec.packets shouldBe FragmentPackets.TlsHello
    }

    @Test
    fun anInvalidLinkFragmentOrNoneMeansAPlainFetch() {
        FetchDecisions.fetchFragment(off, "0-5,10,tlshello") shouldBe null
        FetchDecisions.fetchFragment(off, null) shouldBe null
    }

    @Test
    fun fallbackOnlyForFailuresWhereTheServerSaidNothingUseful() {
        listOf(
            FetchFailure.NotFound,
            FetchFailure.ClientError,
            FetchFailure.ServerError,
            FetchFailure.TimedOut,
            FetchFailure.Unreachable,
            FetchFailure.TlsFailure,
        ).forEach { FetchDecisions.fallbackEligible(it) shouldBe true }
        FetchDecisions.fallbackEligible(FetchFailure.HwidRequired) shouldBe false
        FetchDecisions.fallbackEligible(FetchFailure.DeviceLimitReached) shouldBe false
    }

    @Test
    fun newUrlWinsOverNewDomain() {
        FetchDecisions.moveTarget("https://old.example/s", "https://a.example/x", "b.example") shouldBe
            MoveDecision.Move("https://a.example/x", "old.example")
    }

    @Test
    fun newDomainKeepsPathAndToken() {
        FetchDecisions.moveTarget("https://old.example/p/tok", null, "new.example") shouldBe
            MoveDecision.Move("https://new.example/p/tok", "old.example")
    }

    @Test
    fun aMoveToTheCurrentUrlIsNoMove() {
        FetchDecisions.moveTarget("https://old.example/s", "https://old.example/s", null) shouldBe MoveDecision.None
        FetchDecisions.moveTarget("https://old.example/s", null, "OLD.example") shouldBe MoveDecision.None
    }

    @Test
    fun nothingSentIsNoMoveAndABadTargetIsRejected() {
        FetchDecisions.moveTarget("https://old.example/s", null, null) shouldBe MoveDecision.None
        FetchDecisions.moveTarget("https://old.example/s", "http://a.example/x", null) shouldBe
            MoveDecision.Rejected(RedirectRejection.NotHttps)
    }
}
