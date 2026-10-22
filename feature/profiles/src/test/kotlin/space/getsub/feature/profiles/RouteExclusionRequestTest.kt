// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import space.getsub.core.data.RouteExclusionStatus
import space.getsub.core.data.RouteExclusionTrust
import space.getsub.core.model.CidrRange

class RouteExclusionRequestTest {
    private val lan = CidrRange("172.16.0.0", 12, isV6 = false)
    private val v6 = CidrRange("2001:db8::", 32, isV6 = true)
    private val everything = CidrRange("0.0.0.0", 0, isV6 = false)

    @Test
    fun anUnaskedListAsksAndNamesEveryRange() {
        val request = RouteExclusionStatus(RouteExclusionTrust.Unasked, "fp", listOf(lan, v6)).request()

        request shouldBe
            RouteExclusionRequest("fp", applied = listOf("172.16.0.0/12", "2001:db8::/32"), refused = emptyList())
    }

    @Test
    fun aTooWideRangeIsListedAsNotAppliedAndTheRestIsAsked() {
        val request = RouteExclusionStatus(RouteExclusionTrust.Unasked, "fp", listOf(everything, lan)).request()

        request shouldBe RouteExclusionRequest("fp", applied = listOf("172.16.0.0/12"), refused = listOf("0.0.0.0/0"))
    }

    @Test
    fun nothingApplicableAsksNothing() {
        RouteExclusionStatus(RouteExclusionTrust.Unasked, null, listOf(everything)).request() shouldBe null
        RouteExclusionStatus(RouteExclusionTrust.Unasked, null, emptyList()).request() shouldBe null
    }

    @Test
    fun anAnsweredListAsksNothing() {
        RouteExclusionStatus(RouteExclusionTrust.Allowed, "fp", listOf(lan)).request() shouldBe null
        RouteExclusionStatus(RouteExclusionTrust.Denied, "fp", listOf(lan)).request() shouldBe null
    }

    @Test
    fun theRequestNeverPrintsARange() {
        val text = RouteExclusionStatus(RouteExclusionTrust.Unasked, "fp", listOf(everything, lan)).request().toString()

        text shouldNotContain "172.16.0.0"
        text shouldNotContain "0.0.0.0"
    }
}
