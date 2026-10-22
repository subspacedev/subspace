// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model.subscription

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class SubscriptionInfoTest {
    private val today = LocalDate.of(2026, 11, 10)
    private val utc = ZoneId.of("UTC")

    private fun epoch(date: LocalDate) = date.atTime(12, 0).toEpochSecond(ZoneOffset.UTC)

    private fun quota(
        up: Long? = 0,
        down: Long? = 0,
        total: Long? = 0,
        expire: Long? = 0,
    ) = ProviderQuota(up, down, total, expire)

    private fun info(
        quota: ProviderQuota? = null,
        directives: Map<String, String> = emptyMap(),
        zone: ZoneId = utc,
    ) = subscriptionInfo(directives, quota, today, zone)

    private fun expiryOn(day: Int) = info(quota(expire = epoch(LocalDate.of(2026, 11, day)))).expiry

    @Test
    fun unlimitedWhenTotalIsZero() {
        val i = info(quota(up = 0, down = 2153701362, total = 0, expire = 0))
        i.usage shouldBe Usage.Unlimited(2153701362)
        i.expiry shouldBe Expiry.None
    }

    @Test
    fun limitedWhenTotalPositive() {
        info(quota(up = 1, down = 2, total = 107374182400)).usage shouldBe Usage.Limited(3, 107374182400)
    }

    @Test
    fun noUserInfoDrawsNothing() {
        val i = info(null)
        i.usage shouldBe null
        i.expiry shouldBe null
    }

    @Test
    fun expiryBuckets() {
        expiryOn(14) shouldBe Expiry.On(LocalDate.of(2026, 11, 14), 4)
        (expiryOn(14) as Expiry.On).isSoon shouldBe false
        expiryOn(13) shouldBe Expiry.On(LocalDate.of(2026, 11, 13), 3)
        expiryOn(13)!!.isSoon shouldBe true
        expiryOn(10) shouldBe Expiry.Today
        expiryOn(9) shouldBe Expiry.Expired
        Expiry.None.isSoon shouldBe false
    }

    @Test
    fun zoneDecidesTheCalendarDay() {
        // 22:00 UTC on 11-10 is already 01:00 on 11-11 in Moscow (UTC+3).
        val at = LocalDate.of(2026, 11, 10).atTime(22, 0).toEpochSecond(ZoneOffset.UTC)
        info(quota(expire = at), zone = utc).expiry shouldBe Expiry.Today
        info(quota(expire = at), zone = ZoneId.of("Europe/Moscow")).expiry shouldBe
            Expiry.On(LocalDate.of(2026, 11, 11), 1)
    }

    @Test
    fun safeLinkAllowsOnlyHttpAndHttps() {
        safeLink("https://example.com/a") shouldBe "https://example.com/a"
        safeLink("HTTP://example.com") shouldBe "HTTP://example.com"
        val rejected =
            listOf(
                "javascript:alert(1)",
                "intent://x",
                "file:///sdcard",
                "example.com",
                "tg://resolve?domain=x",
                "https://",
                null,
                "",
            )
        rejected.forEach { safeLink(it) shouldBe null }
    }

    @Test
    fun linksGoThroughSafeLink() {
        val directives =
            mapOf(
                "support-url" to "https://example.com/s",
                "profile-web-page-url" to "javascript:alert(1)",
                "sub-expire-button-link" to "https://example.com/r",
                "sub-info-button-text" to "Go",
                "sub-info-button-link" to "https://example.com/b",
            )
        val i = info(directives = directives)
        i.supportLink shouldBe "https://example.com/s"
        i.accountLink shouldBe null
        i.renewLink shouldBe "https://example.com/r"
        i.infoButton shouldBe LinkButton("Go", "https://example.com/b")
    }

    @Test
    fun infoButtonNeedsTextAndSafeLink() {
        info(directives = mapOf("sub-info-button-text" to "Go")).infoButton shouldBe null
        info(directives = mapOf("sub-info-button-link" to "https://example.com")).infoButton shouldBe null
        info(
            directives = mapOf("sub-info-button-text" to "Go", "sub-info-button-link" to "tg://x"),
        ).infoButton shouldBe null
    }

    @Test
    fun bannerHiddenWhileExpiryIsSoon() {
        val text = mapOf("sub-info-text" to "Hello")
        info(quota(expire = epoch(today)), text).showInfoBanner shouldBe false
        info(quota(expire = epoch(today.plusDays(10))), text).showInfoBanner shouldBe true
        info(null, text).showInfoBanner shouldBe true
        info(null).showInfoBanner shouldBe false
    }

    @Test
    fun textFieldsAreTakenAsStored() {
        val directives =
            mapOf(
                "profile-title" to "Name VPN",
                "announce" to "News",
                "profile-update-interval" to "12",
            )
        val i = info(directives = directives)
        i.title shouldBe "Name VPN"
        i.announce shouldBe "News"
        i.refreshHours shouldBe 12
        info(directives = mapOf("profile-update-interval" to "soon")).refreshHours shouldBe null
    }

    @Test
    fun hasNoColourField() {
        // Compile-level: every field named, and none of them is sub-info-color (M10-B spec: ignored).
        SubscriptionInfo(
            title = null,
            refreshHours = null,
            usage = null,
            expiry = null,
            announce = null,
            infoText = null,
            infoButton = null,
            renewLink = null,
            supportLink = null,
            accountLink = null,
        )
    }
}
