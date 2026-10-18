// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

class ProxyCredentialRulesTest {
    @Test
    fun `usable values pass`() {
        ProxyCredentialRules.check("alice") shouldBe null
        // 127 two-byte characters: 254 UTF-8 bytes.
        ProxyCredentialRules.check("é".repeat(127)) shouldBe null
    }

    @Test
    fun `empty over 255 bytes and control characters fail`() {
        ProxyCredentialRules.check("") shouldBe ProxyCredentialProblem.Empty
        ProxyCredentialRules.check("é".repeat(128)) shouldBe ProxyCredentialProblem.TooLong
        ProxyCredentialRules.check("pa\tss") shouldBe ProxyCredentialProblem.ControlCharacter
        ProxyCredentialRules.check("a\nb") shouldBe ProxyCredentialProblem.ControlCharacter
    }
}
