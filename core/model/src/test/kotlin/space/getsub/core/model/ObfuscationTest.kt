// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

class ObfuscationTest {
    @Test
    fun `a single-value range renders bare and a span renders with a dash`() {
        XrayRange(10, 10).wire() shouldBe "10"
        XrayRange(50, 100).wire() shouldBe "50-100"
    }

    @Test
    fun `a reversed or negative range cannot be built`() {
        shouldThrow<IllegalArgumentException> { XrayRange(5, 1) }
        shouldThrow<IllegalArgumentException> { XrayRange(-1, 3) }
    }

    @Test
    fun `a noise spec needs at least one item`() {
        shouldThrow<IllegalArgumentException> { NoiseSpec(emptyList()) }
    }

    @Test
    fun `toString never prints a noise packet`() {
        val fixed = NoisePayload.Fixed(NoisePacketType.Str, "secret-bytes")
        fixed.toString() shouldNotContain "secret-bytes"
        NoiseSettings.DEFAULT.copy(packet = "secret-bytes").toString() shouldNotContain "secret-bytes"
        val obfuscation =
            Obfuscation(
                fragment = null,
                noise = Applied(NoiseSpec(listOf(NoiseItem(fixed, XrayRange(1, 1)))), replaceOwn = true),
            )
        obfuscation.toString() shouldNotContain "secret-bytes"
    }

    @Test
    fun `defaults are the spec's values`() {
        FragmentSettings.DEFAULT shouldBe FragmentSettings(false, "tlshello", "50-100", "10-20", "10")
        NoiseSettings.DEFAULT shouldBe
            NoiseSettings(false, NoiseMode.Random, NoisePacketType.Str, "", "10-20", "0-255", "10-16")
    }
}
