// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

class ProxyCredentialRulesTest {
    @Test
    fun `usable values pass`() {
        ProxyCredentialRules.checkPass("alice") shouldBe null
        // 127 two-byte characters: 254 UTF-8 bytes.
        ProxyCredentialRules.checkPass("é".repeat(127)) shouldBe null
    }

    @Test
    fun `empty over 255 bytes and control characters fail`() {
        ProxyCredentialRules.checkPass("") shouldBe ProxyCredentialProblem.Empty
        ProxyCredentialRules.checkPass("é".repeat(128)) shouldBe ProxyCredentialProblem.TooLong
        ProxyCredentialRules.checkPass("pa\tss") shouldBe ProxyCredentialProblem.ControlCharacter
        ProxyCredentialRules.checkPass("a\nb") shouldBe ProxyCredentialProblem.ControlCharacter
    }

    @Test
    fun `a colon in the user name fails, a colon in the password passes`() {
        // HTTP Basic splits user:pass at the first colon, so a colon in the user name breaks the HTTP inbound.
        ProxyCredentialRules.checkUser("al:ice") shouldBe ProxyCredentialProblem.Colon
        ProxyCredentialRules.checkUser("alice") shouldBe null
        ProxyCredentialRules.checkUser("") shouldBe ProxyCredentialProblem.Empty
        ProxyCredentialRules.checkPass("pa:ss") shouldBe null
        ProxyCredentialRules.checkPass("pa\tss") shouldBe ProxyCredentialProblem.ControlCharacter
    }
}
