// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.shouldBe
import org.junit.Test

class Hysteria2InputTest {
    @Test
    fun `portList parses singles and ranges`() {
        Hysteria2Input.portList("20000-30000, 40000") shouldBe listOf(20000..30000, 40000..40000)
    }

    @Test
    fun `portList refuses bad ranges and an empty list`() {
        Hysteria2Input.portList("") shouldBe null
        Hysteria2Input.portList("30000-20000") shouldBe null
        Hysteria2Input.portList("0") shouldBe null
        Hysteria2Input.portList("65536") shouldBe null
        Hysteria2Input.portList("80,abc") shouldBe null
    }

    @Test
    fun `bandwidth reads a bare number as Mbps when asked and enforces the floor`() {
        Hysteria2Input.bandwidth("100", bareIsMbps = true) shouldBe "100 mbps"
        Hysteria2Input.bandwidth("100 mbps", bareIsMbps = true) shouldBe "100 mbps"
        Hysteria2Input.bandwidth("1", bareIsMbps = false) shouldBe null
        Hysteria2Input.bandwidth("fast", bareIsMbps = true) shouldBe null
    }

    @Test
    fun `limits are the importer's`() {
        // Hop interval, Gecko range and ALPN live in core:model's Hysteria2Rules (Hysteria2Test).
        Hysteria2Input.MIN_OBFS_PASSWORD shouldBe 4
    }
}
