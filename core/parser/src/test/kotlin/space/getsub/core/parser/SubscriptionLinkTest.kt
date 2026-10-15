// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

class SubscriptionLinkTest {
    @Test
    fun aLinkWithoutHashIsJustTheUrl() {
        SubscriptionLink.parse("  https://sub.example/abc  ") shouldBe
            SubscriptionLink("https://sub.example/abc", null, null, null, null, insecure = false)
    }

    @Test
    fun happsFrontingExampleParses() {
        val link = SubscriptionLink.parse("https://visa.com/123#MyVPN?resolve-address=visa.com&host=mydomain.com")
        link.plainUrl shouldBe "https://visa.com/123"
        link.title shouldBe "MyVPN"
        link.resolveAddress shouldBe "visa.com"
        link.hostHeader shouldBe "mydomain.com"
        link.fragment shouldBe null
        link.insecure shouldBe false
    }

    @Test
    fun happsFragmentExampleParses() {
        val link = SubscriptionLink.parse("https://mydomain.com/123#MyVPN?fragment=80-250,10-100,tlshello")
        link.fragment shouldBe "80-250,10-100,tlshello"
    }

    @Test
    fun aTitleOnlyLinkHasNoParameters() {
        val link = SubscriptionLink.parse("https://a.example/s#Мой%20VPN")
        link.title shouldBe "Мой VPN"
        link.resolveAddress shouldBe null
    }

    @Test
    fun plusInATitleStaysAPlus() {
        SubscriptionLink.parse("https://a.example/s#A+B").title shouldBe "A+B"
    }

    @Test
    fun keysMatchCaseInsensitivelyAndFirstWins() {
        val link = SubscriptionLink.parse("https://a.example/s#T?HOST=one.example&host=two.example")
        link.hostHeader shouldBe "one.example"
    }

    @Test
    fun unknownKeysAndEmptyValuesAreIgnored() {
        val link = SubscriptionLink.parse("https://a.example/s#T?foo=bar&host=&resolve-address=%20")
        link.hostHeader shouldBe null
        link.resolveAddress shouldBe null
    }

    @Test
    fun insecureIsDetectedWithOrWithoutAValue() {
        SubscriptionLink.parse("https://a.example/s#T?insecure").insecure shouldBe true
        SubscriptionLink.parse("https://a.example/s#T?insecure=1").insecure shouldBe true
        SubscriptionLink.parse("https://a.example/s#T?host=x.example").insecure shouldBe false
    }

    @Test
    fun anEmptyTitleIsNull() {
        SubscriptionLink.parse("https://a.example/s#?host=x.example").title shouldBe null
    }

    @Test
    fun malformedPercentEncodingIsKeptVerbatim() {
        SubscriptionLink.parse("https://a.example/s#100%").title shouldBe "100%"
    }

    @Test
    fun toStringRedactsTheUrlAndAddresses() {
        val text =
            SubscriptionLink
                .parse(
                    "https://visa.com/123#T?resolve-address=front.example&host=real.example",
                ).toString()
        text shouldNotContain "visa.com"
        text shouldNotContain "front.example"
        text shouldNotContain "real.example"
    }
}
