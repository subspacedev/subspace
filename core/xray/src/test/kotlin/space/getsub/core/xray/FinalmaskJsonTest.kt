// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.Applied
import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.NoiseItem
import space.getsub.core.model.NoisePacketType
import space.getsub.core.model.NoisePayload
import space.getsub.core.model.NoiseSpec
import space.getsub.core.model.Obfuscation
import space.getsub.core.model.XrayRange

class FinalmaskJsonTest {
    private val fragment =
        FragmentSpec(
            packets = FragmentPackets.TlsHello,
            length = XrayRange(50, 100),
            delay = XrayRange(10, 20),
            maxSplit = XrayRange(10, 10),
        )

    @Test fun `fragment mask uses delay, not freedom's interval`() {
        FinalmaskJson.fragmentMask(fragment) shouldBe
            """{"type":"fragment","settings":{"packets":"tlshello","length":"50-100",""" +
            """"delay":"10-20","maxSplit":"10"}}"""
    }

    @Test fun `absent maxSplit is omitted and a records range is written as a range`() {
        val records = fragment.copy(packets = FragmentPackets.Records(XrayRange(1, 3)), maxSplit = null)
        FinalmaskJson.fragmentMask(records) shouldBe
            """{"type":"fragment","settings":{"packets":"1-3","length":"50-100","delay":"10-20"}}"""
    }

    @Test fun `random noise has no type or packet`() {
        val random = NoisePayload.Random(length = XrayRange(10, 20), byteRange = XrayRange(0, 255))
        val spec = NoiseSpec(listOf(NoiseItem(random, XrayRange(10, 16))))
        FinalmaskJson.noiseMask(spec) shouldBe
            """{"type":"noise","settings":{"noise":[{"rand":"10-20","randRange":"0-255","delay":"10-16"}]}}"""
    }

    @Test fun `fixed noise writes strings escaped and arrays as json arrays`() {
        val spec =
            NoiseSpec(
                listOf(
                    NoiseItem(NoisePayload.Fixed(NoisePacketType.Str, "a\"b"), XrayRange(1, 1)),
                    NoiseItem(NoisePayload.Fixed(NoisePacketType.Array, "1,2,255"), XrayRange(0, 0)),
                ),
            )
        FinalmaskJson.noiseMask(spec) shouldBe
            """{"type":"noise","settings":{"noise":[""" +
            """{"type":"str","packet":"a\"b","delay":"1"},""" +
            """{"type":"array","packet":[1,2,255],"delay":"0"}]}}"""
    }

    @Test fun `streamBlock emits only the halves present, and null when neither is`() {
        FinalmaskJson.streamBlock(Obfuscation(null, null), emptyList()) shouldBe null
        FinalmaskJson.streamBlock(null, emptyList()) shouldBe null
        FinalmaskJson.streamBlock(Obfuscation(Applied(fragment, replaceOwn = true), null), emptyList()) shouldBe
            """{"tcp":[${FinalmaskJson.fragmentMask(fragment)}]}"""
    }
}
