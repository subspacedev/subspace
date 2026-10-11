// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.ObfuscationProblem.Blank
import space.getsub.core.model.ObfuscationProblem.LengthFromZero
import space.getsub.core.model.ObfuscationProblem.NotARange
import space.getsub.core.model.ObfuscationProblem.PacketUndecodable
import space.getsub.core.model.ObfuscationProblem.PacketsFromZero
import space.getsub.core.model.ObfuscationProblem.RandFromZero
import space.getsub.core.model.ObfuscationProblem.RandRangeOver255
import space.getsub.core.model.ObfuscationProblem.Reversed
import space.getsub.core.model.ObfuscationProblem.TooLarge
import space.getsub.core.model.ObfuscationValidation as V

class ObfuscationValidationTest {
    private fun <T> ok(value: T) = Checked.Ok(value)

    private fun bad(problem: ObfuscationProblem) = Checked.Invalid(problem)

    @Test fun `range accepts a span, a bare number and surrounding spaces`() {
        V.range("50-100") shouldBe ok(XrayRange(50, 100))
        V.range("10") shouldBe ok(XrayRange(10, 10))
        V.range(" 1 - 2 ") shouldBe ok(XrayRange(1, 2))
    }

    @Test fun `range refuses blank, junk, negative, reversed and over Int32`() {
        V.range("  ") shouldBe bad(Blank)
        V.range("ten") shouldBe bad(NotARange)
        V.range("-5") shouldBe bad(NotARange)
        V.range("9-3") shouldBe bad(Reversed)
        V.range("1-2147483648") shouldBe bad(TooLarge)
    }

    @Test fun `packets is tlshello in any case, or a range starting at 1`() {
        V.packets("TLSHello") shouldBe ok(FragmentPackets.TlsHello)
        V.packets("1-3") shouldBe ok(FragmentPackets.Records(XrayRange(1, 3)))
        V.packets("0-3") shouldBe bad(PacketsFromZero)
    }

    @Test fun `length minimum must be at least 1`() {
        V.length("0-5") shouldBe bad(LengthFromZero)
        V.length("1-5") shouldBe ok(XrayRange(1, 5))
    }

    @Test fun `delay may be zero`() {
        V.delay("0") shouldBe ok(XrayRange(0, 0))
    }

    @Test fun `maxSplit blank means absent`() {
        V.maxSplit("") shouldBe ok(null)
        V.maxSplit("10") shouldBe ok(XrayRange(10, 10))
    }

    @Test fun `rand must start at 1 and randRange must stay within a byte`() {
        V.rand("0-4") shouldBe bad(RandFromZero)
        V.randRange("0-256") shouldBe bad(RandRangeOver255)
        V.randRange("0-255") shouldBe ok(XrayRange(0, 255))
    }

    @Test fun `packets decode under their type`() {
        V.packet(NoisePacketType.Str, "hello") shouldBe ok("hello")
        V.packet(NoisePacketType.Str, "") shouldBe bad(Blank)
        V.packet(NoisePacketType.Hex, "0aFF") shouldBe ok("0aFF")
        V.packet(NoisePacketType.Hex, "0aF") shouldBe bad(PacketUndecodable)
        V.packet(NoisePacketType.Hex, "zz") shouldBe bad(PacketUndecodable)
        V.packet(NoisePacketType.Base64, "aGVsbG8=") shouldBe ok("aGVsbG8=")
        V.packet(NoisePacketType.Base64, "aGVsbG8") shouldBe bad(PacketUndecodable)
        V.packet(NoisePacketType.Array, "1, 2,255") shouldBe ok("1,2,255")
        V.packet(NoisePacketType.Array, "1,256") shouldBe bad(PacketUndecodable)
    }

    @Test fun `defaults validate`() {
        V.fragment(FragmentSettings.DEFAULT) shouldBe
            ok(FragmentSpec(FragmentPackets.TlsHello, XrayRange(50, 100), XrayRange(10, 20), XrayRange(10, 10)))
        V.noise(NoiseSettings.DEFAULT) shouldBe
            ok(
                NoiseSpec(
                    listOf(NoiseItem(NoisePayload.Random(XrayRange(10, 20), XrayRange(0, 255)), XrayRange(10, 16))),
                ),
            )
    }

    @Test fun `fixed mode uses the packet and ignores rand fields`() {
        val settings =
            NoiseSettings.DEFAULT.copy(mode = NoiseMode.Fixed, packetType = NoisePacketType.Hex, packet = "ff")
        V.noise(settings) shouldBe
            ok(
                NoiseSpec(
                    listOf(NoiseItem(NoisePayload.Fixed(NoisePacketType.Hex, "ff"), XrayRange(10, 16))),
                ),
            )
    }

    @Test fun `the first invalid field is reported`() {
        V.fragment(FragmentSettings.DEFAULT.copy(length = "0-5")) shouldBe bad(LengthFromZero)
    }

    @Test fun `ruling R7 - invalid stored text falls back field by field`() {
        V.fragmentOrDefault(FragmentSettings.DEFAULT.copy(length = "junk", delay = "1-2")) shouldBe
            FragmentSpec(FragmentPackets.TlsHello, XrayRange(50, 100), XrayRange(1, 2), XrayRange(10, 10))
        V.noiseOrDefault(NoiseSettings.DEFAULT.copy(mode = NoiseMode.Fixed, packet = "")) shouldBe
            V.noiseOrDefault(NoiseSettings.DEFAULT)
    }
}
