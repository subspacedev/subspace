// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.Applied
import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.NoiseItem
import space.getsub.core.model.NoisePayload
import space.getsub.core.model.NoiseSpec
import space.getsub.core.model.Obfuscation
import space.getsub.core.model.Profile
import space.getsub.core.model.XrayRange

class XrayConfigGeneratorObfuscationTest {
    private val base = TunnelSettings(socksPort = 10808, dnsServer = "1.1.1.1", enableSniffing = true)
    private val fragment =
        Applied(
            FragmentSpec(
                packets = FragmentPackets.TlsHello,
                length = XrayRange(50, 100),
                delay = XrayRange(10, 20),
                maxSplit = XrayRange(10, 10),
            ),
            replaceOwn = false,
        )
    private val noise =
        Applied(
            NoiseSpec(
                listOf(
                    NoiseItem(
                        NoisePayload.Random(length = XrayRange(10, 20), byteRange = XrayRange(0, 255)),
                        delay = XrayRange(10, 16),
                    ),
                ),
            ),
            replaceOwn = false,
        )

    private fun json(settings: TunnelSettings): String {
        val profile = Profile(id = "id", name = "n", outbound = GOLDEN_OUTBOUND)
        return (XrayConfigGenerator.generate(profile, settings) as ConfigResult.Ok).json
    }

    private fun golden(name: String) = checkNotNull(javaClass.getResource("/golden/$name")).readText().trimEnd()

    @Test fun `null and empty obfuscation are byte-identical to the M1 golden`() {
        json(base) shouldBe golden("vless-reality.json")
        json(base.copy(obfuscation = Obfuscation(null, null))) shouldBe golden("vless-reality.json")
    }

    @Test fun `fragment matches its golden`() {
        json(base.copy(obfuscation = Obfuscation(fragment, null))) shouldBe golden("vless-reality-fragment.json")
    }

    @Test fun `fragment and noise match their golden`() {
        json(base.copy(obfuscation = Obfuscation(fragment, noise))) shouldBe golden("vless-reality-fragment-noise.json")
    }
}
