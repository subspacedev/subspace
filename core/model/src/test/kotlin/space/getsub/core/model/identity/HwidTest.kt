// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model.identity

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import java.security.SecureRandom

class HwidTest {
    @Test
    fun trimsThenAccepts() {
        Hwid.validate("  abcDEF1234=-  ") shouldBe HwidResult.Valid("abcDEF1234=-")
    }

    @Test
    fun rejectsBadShapes() {
        listOf("short", "has space inside", "under_score", "a".repeat(65), "ab\ncd1234567").forEach {
            Hwid.validate(it) shouldBe HwidResult.Invalid
        }
    }

    @Test
    fun acceptsMaxLength() {
        Hwid.validate("a".repeat(64)) shouldBe HwidResult.Valid("a".repeat(64))
    }

    @Test
    fun randomIsWellFormed() {
        val rnd = SecureRandom()
        repeat(1000) {
            val v = Hwid.random(rnd)
            Regex("^[A-Za-z0-9]{32}$").matches(v) shouldBe true
            Hwid.validate(v) shouldBe HwidResult.Valid(v)
        }
    }

    @Test
    fun randomDrawsDiffer() {
        Hwid.random() shouldNotBe Hwid.random()
    }
}
