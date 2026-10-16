// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import io.kotest.matchers.shouldBe
import org.junit.Test

class PreResolveValidationTest {
    @Test
    fun `ip literals`() {
        isIpLiteral("1.1.1.1") shouldBe true
        isIpLiteral("2606:4700::1111") shouldBe true
        isIpLiteral("1.1") shouldBe false
        isIpLiteral("dns.example") shouldBe false
        isIpLiteral("256.1.1.1") shouldBe false
        isIpLiteral(":::") shouldBe false
        isIpLiteral("1:2") shouldBe false
        isIpLiteral("::") shouldBe true
    }

    @Test
    fun `doh urls`() {
        isDohUrl("https://dns.google/dns-query") shouldBe true
        isDohUrl("http://dns.google/x") shouldBe false
        isDohUrl("https://1.1.1.1/dns-query") shouldBe false
        isDohUrl("https://user@dns.google/x") shouldBe false
        isDohUrl("https://[2606:4700::1111]/dns-query") shouldBe false
        isDohUrl("not a url") shouldBe false
    }

    // Each form the connect-time check (RedirectTargets.checkUrl) rejects must fail here too, or it saves silently.
    @Test
    fun `doh urls connect time rejects are rejected`() {
        isDohUrl("https://localhost/dns-query") shouldBe false
        isDohUrl("https://localhost./dns-query") shouldBe false
        isDohUrl("https://doh.localhost/dns-query") shouldBe false
        isDohUrl("https://router.local/dns-query") shouldBe false
        isDohUrl("https://ROUTER.LOCAL/dns-query") shouldBe false
        isDohUrl("https://127.1/dns-query") shouldBe false
        isDohUrl("https://0x7f.1/dns-query") shouldBe false
        isDohUrl("https://2130706433/dns-query") shouldBe false
        isDohUrl("https://:pw@dns.google/dns-query") shouldBe false
        isDohUrl("https:///dns-query") shouldBe false
        isDohUrl("") shouldBe false
    }

    @Test
    fun `doh urls connect time accepts are accepted`() {
        isDohUrl("  https://dns.google/dns-query  ") shouldBe true
        isDohUrl("HTTPS://dns.google/dns-query") shouldBe true
        isDohUrl("https://dns.google:8443/dns-query") shouldBe true
    }
}
