// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.build

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

class ReleaseSigningTest {
    private val values =
        mapOf(
            SigningInput.StoreFile to "/keys/release.jks",
            SigningInput.StorePassword to "s3cr3t-store",
            SigningInput.KeyAlias to "al1as-secret",
            SigningInput.KeyPassword to "s3cr3t-key",
        )

    private fun props(vararg inputs: Pair<SigningInput, String>): (String) -> String? {
        val m = inputs.associate { it.first.gradleProperty to it.second }
        return { m[it] }
    }

    private fun envOf(vararg inputs: Pair<SigningInput, String>): (String) -> String? {
        val m = inputs.associate { it.first.envVar to it.second }
        return { m[it] }
    }

    private val none: (String) -> String? = { null }
    private val signed =
        SigningResolution.Signed("/keys/release.jks", "s3cr3t-store", "al1as-secret", "s3cr3t-key")

    @Test
    fun `nothing set is unsigned`() {
        resolveSigning(none, none) shouldBe SigningResolution.Unsigned
    }

    @Test
    fun `all four via gradle properties`() {
        resolveSigning(props(*values.toList().toTypedArray()), none) shouldBe signed
    }

    @Test
    fun `all four via env`() {
        resolveSigning(none, envOf(*values.toList().toTypedArray())) shouldBe signed
    }

    @Test
    fun `mixed sources`() {
        val p = props(SigningInput.StoreFile to values.getValue(SigningInput.StoreFile), SigningInput.KeyAlias to values.getValue(SigningInput.KeyAlias))
        val e = envOf(SigningInput.StorePassword to values.getValue(SigningInput.StorePassword), SigningInput.KeyPassword to values.getValue(SigningInput.KeyPassword))
        resolveSigning(p, e) shouldBe signed
    }

    @Test
    fun `property wins over env`() {
        val p = props(*values.toList().toTypedArray())
        val e = envOf(SigningInput.StorePassword to "from-env")
        resolveSigning(p, e) shouldBe signed
    }

    @Test
    fun `blank counts as missing`() {
        for (blank in listOf("", "   ")) {
            val all = values + (SigningInput.KeyPassword to blank)
            resolveSigning(props(*all.toList().toTypedArray()), none) shouldBe
                SigningResolution.Incomplete(listOf(SigningInput.KeyPassword))
        }
    }

    @Test
    fun `incomplete message names the input and leaks no value`() {
        val partial = values - SigningInput.KeyPassword
        val result = resolveSigning(props(*partial.toList().toTypedArray()), none)
        val message = (result as SigningResolution.Incomplete).message()
        message shouldContain "subspace.signing.keyPassword"
        message shouldContain "SUBSPACE_SIGNING_KEY_PASSWORD"
        for (v in partial.values) message shouldNotContain v
    }

    @Test
    fun `signed toString hides password and alias`() {
        val text = signed.toString()
        text shouldNotContain "s3cr3t-store"
        text shouldNotContain "s3cr3t-key"
        text shouldNotContain "al1as-secret"
        text shouldNotBe ""
    }
}
