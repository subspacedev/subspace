// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.subscription.Expiry
import space.getsub.core.model.subscription.SubscriptionInfo
import space.getsub.core.model.subscription.Usage
import java.time.LocalDate

/** M10-B spec §3.3: Home shows a line only when a limit or an expiry applies. */
class HomeSubscriptionLineTest {
    private fun info(
        usage: Usage? = null,
        expiry: Expiry? = null,
        renewLink: String? = null,
    ) = SubscriptionInfo(
        title = null,
        refreshHours = null,
        usage = usage,
        expiry = expiry,
        announce = "an announcement",
        infoText = "an info banner",
        infoButton = null,
        renewLink = renewLink,
        supportLink = "https://example.com/support",
        accountLink = "https://example.com/account",
    )

    private val limited = Usage.Limited(usedBytes = 38L shl 30, totalBytes = 100L shl 30)
    private val inTenDays = Expiry.On(LocalDate.of(2026, 11, 14), daysLeft = 10)
    private val inTwoDays = Expiry.On(LocalDate.of(2026, 11, 6), daysLeft = 2)

    @Test
    fun `a manual server has no line`() {
        homeSubscriptionLine(null) shouldBe null
    }

    @Test
    fun `unlimited with no expiry has no line`() {
        homeSubscriptionLine(info(usage = Usage.Unlimited(5L), expiry = Expiry.None)) shouldBe null
    }

    @Test
    fun `no counters and no expiry has no line`() {
        homeSubscriptionLine(info()) shouldBe null
    }

    @Test
    fun `a limit alone gives a usage line`() {
        homeSubscriptionLine(info(usage = limited, expiry = Expiry.None)) shouldBe
            HomeSubscriptionLine(usage = limited, expiry = null, renewLink = null)
    }

    @Test
    fun `unlimited with an expiry shows the expiry only`() {
        homeSubscriptionLine(info(usage = Usage.Unlimited(5L), expiry = inTenDays)) shouldBe
            HomeSubscriptionLine(usage = null, expiry = inTenDays, renewLink = null)
    }

    @Test
    fun `an expiry alone ten days out gives a date line`() {
        homeSubscriptionLine(info(expiry = inTenDays)) shouldBe
            HomeSubscriptionLine(usage = null, expiry = inTenDays, renewLink = null)
    }

    @Test
    fun `an expiry two days out carries the renew link`() {
        homeSubscriptionLine(info(expiry = inTwoDays, renewLink = "https://example.com/renew")) shouldBe
            HomeSubscriptionLine(usage = null, expiry = inTwoDays, renewLink = "https://example.com/renew")
    }

    @Test
    fun `a renew link is kept only while the expiry is soon`() {
        homeSubscriptionLine(info(expiry = inTenDays, renewLink = "https://example.com/renew")) shouldBe
            HomeSubscriptionLine(usage = null, expiry = inTenDays, renewLink = null)
    }
}
