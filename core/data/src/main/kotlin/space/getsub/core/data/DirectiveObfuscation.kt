// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import space.getsub.core.model.Applied
import space.getsub.core.model.Checked
import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.NoiseItem
import space.getsub.core.model.NoisePacketType
import space.getsub.core.model.NoisePayload
import space.getsub.core.model.NoiseSettings
import space.getsub.core.model.NoiseSpec
import space.getsub.core.model.Obfuscation
import space.getsub.core.model.ObfuscationProblem
import space.getsub.core.model.ObfuscationValidation

public enum class ObfuscationSource { User, Directive }

/** One mask's resolution (M9-A spec §3.1). [Ignored] carries the reason the UI shows. */
public sealed interface MaskResolution<out T> {
    public data class Resolved<T>(val spec: T, val source: ObfuscationSource) : MaskResolution<T> {
        override fun toString(): String = "Resolved(source=$source)" // ARCHITECTURE.md §5.6
    }

    public data class Ignored(val problem: ObfuscationProblem) : MaskResolution<Nothing>

    public data object Absent : MaskResolution<Nothing>
}

public data class ResolvedObfuscation(
    val fragment: MaskResolution<FragmentSpec>,
    val noise: MaskResolution<NoiseSpec>,
) {
    /** What the config writers get. replaceOwn is true exactly for the user's values (ruling R1). */
    public fun toObfuscation(): Obfuscation? {
        val f = (fragment as? MaskResolution.Resolved)?.let { Applied(it.spec, it.source == ObfuscationSource.User) }
        val n = (noise as? MaskResolution.Resolved)?.let { Applied(it.spec, it.source == ObfuscationSource.User) }
        return if (f == null && n == null) null else Obfuscation(f, n)
    }
}

private const val FRAGMENT_ENABLE = "fragmentation-enable"
private const val FRAGMENT_PACKETS = "fragmentation-packets"
private const val FRAGMENT_LENGTH = "fragmentation-length"
private const val FRAGMENT_INTERVAL = "fragmentation-interval"
private const val FRAGMENT_MAX_SPLIT = "fragmentation-maxsplit"
private const val NOISE_ENABLE = "noises-enable"
private const val NOISE_TYPE = "noises-packet-type"
private const val NOISE_PACKET = "noises-packet"
private const val NOISE_DELAY = "noises-delay"
private const val NOISE_RAND = "noises-rand"
private const val NOISE_RAND_RANGE = "noises-rand-range"

/** Every directive key this file reads; the resolver fetches exactly these. */
public val OBFUSCATION_DIRECTIVE_KEYS: List<String> =
    listOf(
        FRAGMENT_ENABLE, FRAGMENT_PACKETS, FRAGMENT_LENGTH, FRAGMENT_INTERVAL, FRAGMENT_MAX_SPLIT,
        NOISE_ENABLE, NOISE_TYPE, NOISE_PACKET, NOISE_DELAY, NOISE_RAND, NOISE_RAND_RANGE,
    )

/**
 * M9-A spec §1.1 and §3.1: the user's setting, when On, wins outright. Otherwise the directives
 * apply, a missing field takes the default, and an invalid field drops that mask only.
 */
public fun resolveObfuscation(
    fragment: FragmentSettings,
    noise: NoiseSettings,
    directives: Map<String, String?>,
): ResolvedObfuscation =
    ResolvedObfuscation(
        fragment =
        if (fragment.enabled) {
            MaskResolution.Resolved(ObfuscationValidation.fragmentOrDefault(fragment), ObfuscationSource.User)
        } else {
            fragmentFromDirectives(directives)
        },
        noise =
        if (noise.enabled) {
            MaskResolution.Resolved(ObfuscationValidation.noiseOrDefault(noise), ObfuscationSource.User)
        } else {
            noiseFromDirectives(directives)
        },
    )

@Suppress("ReturnCount") // One return per distinct refusal.
private fun fragmentFromDirectives(d: Map<String, String?>): MaskResolution<FragmentSpec> {
    if (!isDirectiveEnabled(d[FRAGMENT_ENABLE])) return MaskResolution.Absent
    val packets = d[FRAGMENT_PACKETS]?.takeIf(String::isNotBlank)
    if (packets != null && packets.contains(',')) return MaskResolution.Ignored(ObfuscationProblem.MultiplePackets)
    val defaults = FragmentSettings.DEFAULT
    val settings =
        defaults.copy(
            enabled = true,
            packets = packets ?: defaults.packets,
            length = d[FRAGMENT_LENGTH].orDefault(defaults.length),
            delay = d[FRAGMENT_INTERVAL].orDefault(defaults.delay),
            maxSplit = d[FRAGMENT_MAX_SPLIT].orDefault(defaults.maxSplit),
        )
    return when (val checked = ObfuscationValidation.fragment(settings)) {
        is Checked.Ok -> MaskResolution.Resolved(checked.value, ObfuscationSource.Directive)
        is Checked.Invalid -> MaskResolution.Ignored(checked.problem)
    }
}

@Suppress("ReturnCount") // One return per distinct refusal.
private fun noiseFromDirectives(d: Map<String, String?>): MaskResolution<NoiseSpec> {
    if (!isDirectiveEnabled(d[NOISE_ENABLE])) return MaskResolution.Absent
    val defaults = NoiseSettings.DEFAULT
    val delay =
        when (val checked = ObfuscationValidation.delay(d[NOISE_DELAY].orDefault(defaults.delay))) {
            is Checked.Ok -> checked.value
            is Checked.Invalid -> return MaskResolution.Ignored(checked.problem)
        }
    val packet = d[NOISE_PACKET]?.takeIf(String::isNotBlank)
    val rand = d[NOISE_RAND]?.takeIf { !isZeroRand(it) }
    if (packet != null && rand != null) return MaskResolution.Ignored(ObfuscationProblem.PacketAndRand)

    val payloads =
        if (packet != null) fixedPayloads(d[NOISE_TYPE], packet) else randomPayload(rand, d[NOISE_RAND_RANGE])
    return when (payloads) {
        is Checked.Ok ->
            MaskResolution.Resolved(
                NoiseSpec(payloads.value.map { NoiseItem(it, delay) }),
                ObfuscationSource.Directive,
            )
        is Checked.Invalid -> MaskResolution.Ignored(payloads.problem)
    }
}

// "Zero" is numeric, so "0", "00" and "0-0" all mean no random length.
private fun isZeroRand(text: String): Boolean =
    text.isBlank() || (ObfuscationValidation.range(text) as? Checked.Ok)?.value?.to == 0

private fun fixedPayloads(
    typeText: String?,
    packet: String,
): Checked<List<NoisePayload>> {
    // Ruling R3: no type means str. An unknown, non-blank type is a refusal, not str.
    val typeName = typeText?.trim().orEmpty()
    val type =
        if (typeName.isEmpty()) {
            NoisePacketType.Str
        } else {
            NoisePacketType.entries.firstOrNull { it.wire.equals(typeName, ignoreCase = true) }
        }
    // Ruling R2: an array CSV is one packet's bytes; any other type is one packet per entry.
    val entries =
        when (type) {
            null -> emptyList()
            NoisePacketType.Array -> listOf(packet)
            else -> packet.split(',').filter(String::isNotBlank)
        }
    return when {
        type == null -> Checked.Invalid(ObfuscationProblem.PacketUndecodable)
        entries.isEmpty() -> Checked.Invalid(ObfuscationProblem.Blank)
        else -> checkedPayloads(type, entries)
    }
}

private fun checkedPayloads(
    type: NoisePacketType,
    entries: List<String>,
): Checked<List<NoisePayload>> {
    val payloads = mutableListOf<NoisePayload>()
    for (entry in entries) {
        when (val checked = ObfuscationValidation.packet(type, entry)) {
            is Checked.Ok -> payloads += NoisePayload.Fixed(type, checked.value)
            is Checked.Invalid -> return checked
        }
    }
    return Checked.Ok(payloads)
}

private fun randomPayload(
    rand: String?,
    randRange: String?,
): Checked<List<NoisePayload>> {
    val defaults = NoiseSettings.DEFAULT
    val length = ObfuscationValidation.rand(rand ?: defaults.rand)
    val byteRange = ObfuscationValidation.randRange(randRange.orDefault(defaults.randRange))
    return when {
        length is Checked.Invalid -> length
        byteRange is Checked.Invalid -> byteRange
        else -> Checked.Ok(listOf(NoisePayload.Random((length as Checked.Ok).value, (byteRange as Checked.Ok).value)))
    }
}

private fun String?.orDefault(default: String): String = this?.takeIf(String::isNotBlank) ?: default
