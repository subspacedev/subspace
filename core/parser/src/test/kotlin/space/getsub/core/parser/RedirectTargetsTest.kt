// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.shouldBe
import org.junit.Test

class RedirectTargetsTest {
    @Test
    fun anHttpsUrlIsAccepted() {
        RedirectTargets.checkUrl("https://New.Example/sub/x?a=1") shouldBe
            RedirectCheck.Ok("https://New.Example/sub/x?a=1", "new.example")
    }

    @Test
    fun everyRejectionClass() {
        RedirectTargets.checkUrl("http://a.example/s") shouldBe RedirectCheck.Rejected(RedirectRejection.NotHttps)
        RedirectTargets.checkUrl("not a url") shouldBe RedirectCheck.Rejected(RedirectRejection.Malformed)
        RedirectTargets.checkUrl("https:///nohost") shouldBe RedirectCheck.Rejected(RedirectRejection.Malformed)
        RedirectTargets.checkUrl("https://u:p@a.example/s") shouldBe
            RedirectCheck.Rejected(RedirectRejection.HasUserInfo)
        RedirectTargets.checkUrl("https://203.0.113.7/s") shouldBe RedirectCheck.Rejected(RedirectRejection.IpLiteral)
        RedirectTargets.checkUrl("https://[2001:db8::1]/s") shouldBe RedirectCheck.Rejected(RedirectRejection.IpLiteral)
        RedirectTargets.checkUrl("https://localhost/s") shouldBe RedirectCheck.Rejected(RedirectRejection.LocalHost)
        RedirectTargets.checkUrl("https://box.local/s") shouldBe RedirectCheck.Rejected(RedirectRejection.LocalHost)
        RedirectTargets.checkUrl("https://a.localhost/s") shouldBe RedirectCheck.Rejected(RedirectRejection.LocalHost)
    }

    @Test
    fun applyDomainSwapsOnlyTheHost() {
        RedirectTargets.applyDomain("https://old.example:8443/p/3J3jrb?x=1", "mynew-domain.com") shouldBe
            RedirectCheck.Ok("https://mynew-domain.com:8443/p/3J3jrb?x=1", "mynew-domain.com")
    }

    @Test
    fun applyDomainRefusesAnythingButABareHostname() {
        listOf("https://x.example", "x.example/path", "x.example:443", "u@x.example", "nodot", "-bad.example")
            .forEach {
                RedirectTargets.applyDomain("https://old.example/s", it) shouldBe
                    RedirectCheck.Rejected(RedirectRejection.NotABareHost)
            }
        RedirectTargets.applyDomain("https://old.example/s", "203.0.113.7") shouldBe
            RedirectCheck.Rejected(RedirectRejection.IpLiteral)
    }

    @Test
    fun applyDomainKeepsTheHttpsRuleOfTheResult() {
        RedirectTargets.applyDomain("http://old.example/s", "new.example") shouldBe
            RedirectCheck.Rejected(RedirectRejection.NotHttps)
    }
}
