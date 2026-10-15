// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network.preresolve

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class IpLiteralTest {
    @Test fun ipv4Literal() { IpLiteral.parse("1.1.1.1")?.hostAddress shouldBe "1.1.1.1" }

    @Test fun ipv6Literal() { IpLiteral.parse("2606:4700:4700::1111") shouldNotBe null }

    @Test fun bracketedIpv6IsNotAccepted() { IpLiteral.parse("[::1]") shouldBe null }

    @Test fun shorthandIpv4IsRejected() { IpLiteral.parse("1.1") shouldBe null }

    @Test fun octetOutOfRange() { IpLiteral.parse("256.1.1.1") shouldBe null }

    @Test fun hostnameIsNotALiteralAndIsNeverLookedUp() { IpLiteral.parse("example.com") shouldBe null }

    @Test fun blank() { IpLiteral.parse(" ") shouldBe null }

    @Test fun leadingZerosRejected() { IpLiteral.parse("01.1.1.1") shouldBe null }

    @Test fun isLiteralMirrorsParse() {
        IpLiteral.isLiteral("1.1.1.1") shouldBe true
        IpLiteral.isLiteral("example.com") shouldBe false
    }
}
