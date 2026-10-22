// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model.identity

import io.kotest.matchers.shouldBe
import org.junit.Test

class HeaderValueTest {
    @Test
    fun literalAcceptsPrintable() {
        HeaderValue.validateLiteral("Pixel 8") shouldBe "Pixel 8"
        HeaderValue.validateLiteral("a".repeat(128)) shouldBe "a".repeat(128)
    }

    @Test
    fun literalRejects() {
        listOf("", "  ", "a\r\nX-Evil: 1", "Пиксель", "a".repeat(129)).forEach {
            HeaderValue.validateLiteral(it) shouldBe null
        }
    }

    @Test
    fun sanitiseKeepsPrintable() {
        HeaderValue.sanitiseDevice("Pixel 8") shouldBe "Pixel 8"
        HeaderValue.sanitiseDevice("Redmi Note 12 Pro+ 5G") shouldBe "Redmi Note 12 Pro+ 5G"
    }

    @Test
    fun sanitiseStripsNonAscii() {
        HeaderValue.sanitiseDevice("华为 P60") shouldBe "P60"
    }

    @Test
    fun sanitiseEmptyIsNull() {
        HeaderValue.sanitiseDevice("​") shouldBe null
    }

    @Test
    fun sanitiseTruncates() {
        HeaderValue.sanitiseDevice("a".repeat(200)) shouldBe "a".repeat(128)
    }
}
