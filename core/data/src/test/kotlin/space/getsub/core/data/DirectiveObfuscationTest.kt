// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.NoiseItem
import space.getsub.core.model.NoisePacketType
import space.getsub.core.model.NoisePayload
import space.getsub.core.model.NoiseSettings
import space.getsub.core.model.NoiseSpec
import space.getsub.core.model.ObfuscationProblem
import space.getsub.core.model.XrayRange

class DirectiveObfuscationTest {
    private val off = FragmentSettings.DEFAULT to NoiseSettings.DEFAULT

    private fun resolve(
        directives: Map<String, String?>,
        fragment: FragmentSettings = off.first,
        noise: NoiseSettings = off.second,
    ) = resolveObfuscation(fragment, noise, directives)

    private val defaultFragment =
        FragmentSpec(FragmentPackets.TlsHello, XrayRange(50, 100), XrayRange(10, 20), XrayRange(10, 10))

    @Test fun `nothing set and nothing sent resolves to nothing`() {
        resolve(emptyMap()) shouldBe ResolvedObfuscation(MaskResolution.Absent, MaskResolution.Absent)
        resolve(emptyMap()).toObfuscation() shouldBe null
    }

    @Test fun `review focus 1 - user On with untouched defaults uses the defaults and replaces own`() {
        val r = resolve(emptyMap(), fragment = FragmentSettings.DEFAULT.copy(enabled = true))
        r.fragment shouldBe MaskResolution.Resolved(defaultFragment, ObfuscationSource.User)
        r.toObfuscation()!!.fragment!!.replaceOwn shouldBe true
    }

    @Test fun `user On ignores the directives entirely`() {
        val r =
            resolve(
                mapOf("fragmentation-enable" to "1", "fragmentation-length" to "0-5"),
                fragment = FragmentSettings.DEFAULT.copy(enabled = true),
            )
        r.fragment shouldBe MaskResolution.Resolved(defaultFragment, ObfuscationSource.User)
    }

    @Test fun `review focus 2 - enable alone fills every field from the defaults`() {
        resolve(mapOf("fragmentation-enable" to "1")).fragment shouldBe
            MaskResolution.Resolved(defaultFragment, ObfuscationSource.Directive)
    }

    @Test fun `interval maps to delay and the other fields map by name`() {
        val r =
            resolve(
                mapOf(
                    "fragmentation-enable" to "true",
                    "fragmentation-packets" to "1-3",
                    "fragmentation-length" to "5-9",
                    "fragmentation-interval" to "2",
                    "fragmentation-maxsplit" to "4-6",
                ),
            )
        r.fragment shouldBe
            MaskResolution.Resolved(
                FragmentSpec(
                    FragmentPackets.Records(XrayRange(1, 3)),
                    XrayRange(5, 9),
                    XrayRange(2, 2),
                    XrayRange(4, 6),
                ),
                ObfuscationSource.Directive,
            )
        r.toObfuscation()!!.fragment!!.replaceOwn shouldBe false
    }

    @Test fun `only true or 1 enables - ARCHITECTURE A1`() {
        resolve(mapOf("fragmentation-enable" to "yes")).fragment shouldBe MaskResolution.Absent
    }

    @Test fun `an invalid field drops that mask only, with its reason`() {
        val r = resolve(mapOf("fragmentation-enable" to "1", "fragmentation-length" to "0-5", "noises-enable" to "1"))
        r.fragment shouldBe MaskResolution.Ignored(ObfuscationProblem.LengthFromZero)
        (r.noise is MaskResolution.Resolved) shouldBe true
    }

    @Test fun `more than one packets entry is invalid`() {
        resolve(mapOf("fragmentation-enable" to "1", "fragmentation-packets" to "tlshello,1-2")).fragment shouldBe
            MaskResolution.Ignored(ObfuscationProblem.MultiplePackets)
    }

    @Test fun `blank packets entries are ignored, so one real entry is still one`() {
        resolve(mapOf("fragmentation-enable" to "1", "fragmentation-packets" to "tlshello,")).fragment shouldBe
            MaskResolution.Resolved(defaultFragment, ObfuscationSource.Directive)
        resolve(mapOf("fragmentation-enable" to "1", "fragmentation-packets" to ",")).fragment shouldBe
            MaskResolution.Resolved(defaultFragment, ObfuscationSource.Directive)
        resolve(mapOf("fragmentation-enable" to "1", "fragmentation-packets" to "1-2,,tlshello")).fragment shouldBe
            MaskResolution.Ignored(ObfuscationProblem.MultiplePackets)
    }

    @Test fun `noise enable alone is the default random item`() {
        resolve(mapOf("noises-enable" to "1")).noise shouldBe
            MaskResolution.Resolved(
                NoiseSpec(
                    listOf(NoiseItem(NoisePayload.Random(XrayRange(10, 20), XrayRange(0, 255)), XrayRange(10, 16))),
                ),
                ObfuscationSource.Directive,
            )
    }

    @Test fun `each csv packet is one item, delay d becomes d-d, default type is str - ruling R3`() {
        resolve(mapOf("noises-enable" to "1", "noises-packet" to "ab,cd", "noises-delay" to "7")).noise shouldBe
            MaskResolution.Resolved(
                NoiseSpec(
                    listOf(
                        NoiseItem(NoisePayload.Fixed(NoisePacketType.Str, "ab"), XrayRange(7, 7)),
                        NoiseItem(NoisePayload.Fixed(NoisePacketType.Str, "cd"), XrayRange(7, 7)),
                    ),
                ),
                ObfuscationSource.Directive,
            )
    }

    @Test fun `an array packet csv is one item - ruling R2`() {
        resolve(
            mapOf("noises-enable" to "1", "noises-packet-type" to "array", "noises-packet" to "1,2,3"),
        ).noise shouldBe
            MaskResolution.Resolved(
                NoiseSpec(listOf(NoiseItem(NoisePayload.Fixed(NoisePacketType.Array, "1,2,3"), XrayRange(10, 16)))),
                ObfuscationSource.Directive,
            )
    }

    @Test fun `packet and a nonzero rand together are invalid`() {
        resolve(mapOf("noises-enable" to "1", "noises-packet" to "ab", "noises-rand" to "3-4")).noise shouldBe
            MaskResolution.Ignored(ObfuscationProblem.PacketAndRand)
    }

    @Test fun `rand and rand-range map to a random item`() {
        resolve(mapOf("noises-enable" to "1", "noises-rand" to "3-4", "noises-rand-range" to "1-9")).noise shouldBe
            MaskResolution.Resolved(
                NoiseSpec(listOf(NoiseItem(NoisePayload.Random(XrayRange(3, 4), XrayRange(1, 9)), XrayRange(10, 16)))),
                ObfuscationSource.Directive,
            )
    }

    @Test fun `toString hides directive values`() {
        val r = resolve(mapOf("noises-enable" to "1", "noises-packet" to "secret"))
        r.toString().contains("secret") shouldBe false
    }

    @Test fun `a negative or non-numeric noise delay is ignored, not thrown`() {
        resolve(mapOf("noises-enable" to "1", "noises-delay" to "-5")).noise shouldBe
            MaskResolution.Ignored(ObfuscationProblem.NotARange)
        resolve(mapOf("noises-enable" to "1", "noises-delay" to "abc")).noise shouldBe
            MaskResolution.Ignored(ObfuscationProblem.NotARange)
    }

    @Test fun `a noise delay may be a range`() {
        val noise = resolve(mapOf("noises-enable" to "1", "noises-delay" to "5-10")).noise
        (noise as MaskResolution.Resolved).spec.items.single().delay shouldBe XrayRange(5, 10)
    }

    @Test fun `a packet of only commas is blank`() {
        resolve(mapOf("noises-enable" to "1", "noises-packet" to ",")).noise shouldBe
            MaskResolution.Ignored(ObfuscationProblem.Blank)
        resolve(mapOf("noises-enable" to "1", "noises-packet" to " , ")).noise shouldBe
            MaskResolution.Ignored(ObfuscationProblem.Blank)
    }

    @Test fun `the packet type matches ignoring case`() {
        resolve(mapOf("noises-enable" to "1", "noises-packet-type" to "HEX", "noises-packet" to "0aff")).noise shouldBe
            MaskResolution.Resolved(
                NoiseSpec(listOf(NoiseItem(NoisePayload.Fixed(NoisePacketType.Hex, "0aff"), XrayRange(10, 16)))),
                ObfuscationSource.Directive,
            )
    }

    @Test fun `an unknown packet type is ignored`() {
        resolve(mapOf("noises-enable" to "1", "noises-packet-type" to "foo", "noises-packet" to "ab")).noise shouldBe
            MaskResolution.Ignored(ObfuscationProblem.PacketUndecodable)
    }

    @Test fun `a numerically zero rand does not clash with a packet`() {
        val noise = resolve(mapOf("noises-enable" to "1", "noises-packet" to "ab", "noises-rand" to "0-0")).noise
        (noise is MaskResolution.Resolved) shouldBe true
    }
}
