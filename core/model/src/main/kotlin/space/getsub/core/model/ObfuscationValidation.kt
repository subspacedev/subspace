// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

/** Why a fragment/noise value is refused. The UI maps each member to a string (`:core:ui`). */
public enum class ObfuscationProblem {
    Blank,
    NotARange,
    Reversed,
    TooLarge,
    OutOfBounds,
    PacketsFromZero,
    LengthFromZero,
    RandFromZero,
    RandRangeOver255,
    PacketUndecodable,
    PacketAndRand,
    MultiplePackets,
}

public sealed interface Checked<out T> {
    public data class Ok<T>(
        val value: T,
    ) : Checked<T>

    public data class Invalid(
        val problem: ObfuscationProblem,
    ) : Checked<Nothing>
}

/**
 * The one validator for fragment and noise values (M9-A spec §3.2), shared by the settings page and
 * the directive mapping. It mirrors Xray's own refusals in `transport_finalmask.go`
 * (`FragmentMask.Build`, `NoiseMask.Build`, `PraseByteSlice`) at `50231eaff98c`. It is our belief
 * about Xray, and the real-libXray test is the authority (ARCHITECTURE.md §10.5).
 */
@Suppress("TooManyFunctions") // One function per field; each is a distinct rule.
public object ObfuscationValidation {
    private val RANGE = Regex("""^(\d{1,10})(?:\s*-\s*(\d{1,10}))?$""")
    private val HEX = Regex("^(?:[0-9a-fA-F]{2})+$")
    private val BASE64 = Regex("^[A-Za-z0-9+/]*={0,2}$")
    private const val MAX_BYTE = 255

    // Audit finding 5 (2026-10-13): Xray's own ceiling is Int32, but a delay near it parks the fetch
    // thread for weeks (Thread.sleep is not interruptible by the call timeout) and a length or count
    // past these is meaningless: a TLS record is at most 16384 bytes, and `packets` and `maxSplit` count
    // writes and pieces. Noise shares `delay()`, so its delay is capped the same way (1 s is far above
    // M9-A's 10-16 ms default).
    private const val MAX_LENGTH = 16_384
    private const val MAX_DELAY_MS = 1_000
    private const val MAX_COUNT = 255
    private const val BASE64_QUANTUM = 4

    public fun range(text: String): Checked<XrayRange> {
        val trimmed = text.trim()
        val match = RANGE.matchEntire(trimmed)
        return when {
            trimmed.isEmpty() -> Checked.Invalid(ObfuscationProblem.Blank)
            match == null -> Checked.Invalid(ObfuscationProblem.NotARange)
            else -> spanOf(match.groupValues[1], match.groupValues[2].ifEmpty { match.groupValues[1] })
        }
    }

    private fun spanOf(
        fromText: String,
        toText: String,
    ): Checked<XrayRange> {
        val from = fromText.toLong()
        val to = toText.toLong()
        return when {
            from > Int.MAX_VALUE || to > Int.MAX_VALUE -> Checked.Invalid(ObfuscationProblem.TooLarge)
            from > to -> Checked.Invalid(ObfuscationProblem.Reversed)
            else -> Checked.Ok(XrayRange(from.toInt(), to.toInt()))
        }
    }

    public fun packets(text: String): Checked<FragmentPackets> {
        if (text.trim().equals("tlshello", ignoreCase = true)) return Checked.Ok(FragmentPackets.TlsHello)
        return range(text)
            .requireFrom(1, ObfuscationProblem.PacketsFromZero)
            .requireAtMost(MAX_COUNT)
            .map(FragmentPackets::Records)
    }

    public fun length(text: String): Checked<XrayRange> =
        range(text).requireFrom(1, ObfuscationProblem.LengthFromZero).requireAtMost(MAX_LENGTH)

    public fun delay(text: String): Checked<XrayRange> = range(text).requireAtMost(MAX_DELAY_MS)

    public fun maxSplit(text: String): Checked<XrayRange?> {
        if (text.isBlank()) return Checked.Ok(null)
        return range(text).requireAtMost(MAX_COUNT)
    }

    public fun rand(text: String): Checked<XrayRange> = range(text).requireFrom(1, ObfuscationProblem.RandFromZero)

    public fun randRange(text: String): Checked<XrayRange> =
        when (val checked = range(text)) {
            is Checked.Invalid -> checked
            is Checked.Ok ->
                if (checked.value.to > MAX_BYTE) Checked.Invalid(ObfuscationProblem.RandRangeOver255) else checked
        }

    /** Returns the packet normalised for emission (an array loses its spaces). */
    public fun packet(
        type: NoisePacketType,
        text: String,
    ): Checked<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Checked.Invalid(ObfuscationProblem.Blank)
        val normalised =
            when (type) {
                NoisePacketType.Str -> text
                NoisePacketType.Hex -> trimmed.takeIf { HEX.matches(it) }
                // Go's StdEncoding: padded, standard alphabet.
                NoisePacketType.Base64 ->
                    trimmed.takeIf { it.length % BASE64_QUANTUM == 0 && BASE64.matches(it) }
                NoisePacketType.Array ->
                    trimmed
                        .split(',')
                        .map { it.trim().toIntOrNull() }
                        .takeIf { bytes -> bytes.all { it != null && it in 0..MAX_BYTE } }
                        ?.joinToString(",")
            }
        return if (normalised == null) {
            Checked.Invalid(ObfuscationProblem.PacketUndecodable)
        } else {
            Checked.Ok(normalised)
        }
    }

    public fun fragment(settings: FragmentSettings): Checked<FragmentSpec> =
        packets(settings.packets).flatMap { packets ->
            length(settings.length).flatMap { length ->
                delay(settings.delay).flatMap { delay ->
                    maxSplit(settings.maxSplit).map { maxSplit -> FragmentSpec(packets, length, delay, maxSplit) }
                }
            }
        }

    public fun noise(settings: NoiseSettings): Checked<NoiseSpec> {
        val payload: Checked<NoisePayload> =
            when (settings.mode) {
                NoiseMode.Random ->
                    rand(settings.rand).flatMap { rand ->
                        randRange(settings.randRange).map { byteRange -> NoisePayload.Random(rand, byteRange) }
                    }
                NoiseMode.Fixed ->
                    packet(settings.packetType, settings.packet).map { packet ->
                        NoisePayload.Fixed(settings.packetType, packet)
                    }
            }
        return payload.flatMap { item ->
            delay(settings.delay).map { delay -> NoiseSpec(listOf(NoiseItem(item, delay))) }
        }
    }

    /** M9-A plan ruling R7: each invalid field takes [FragmentSettings.DEFAULT]'s value. Never fails. */
    public fun fragmentOrDefault(settings: FragmentSettings): FragmentSpec {
        val d = FragmentSettings.DEFAULT
        val repaired =
            settings.copy(
                packets = settings.packets.takeIf { packets(it) is Checked.Ok } ?: d.packets,
                length = settings.length.takeIf { length(it) is Checked.Ok } ?: d.length,
                delay = settings.delay.takeIf { delay(it) is Checked.Ok } ?: d.delay,
                maxSplit = settings.maxSplit.takeIf { maxSplit(it) is Checked.Ok } ?: d.maxSplit,
            )
        return (fragment(repaired) as Checked.Ok).value
    }

    /** Ruling R7 for noise. An invalid fixed packet falls back to the default (random) payload. */
    public fun noiseOrDefault(settings: NoiseSettings): NoiseSpec {
        val d = NoiseSettings.DEFAULT
        val delay = settings.delay.takeIf { delay(it) is Checked.Ok } ?: d.delay
        val asIs = noise(settings.copy(delay = delay))
        if (asIs is Checked.Ok) return asIs.value
        val random =
            settings.copy(
                mode = NoiseMode.Random,
                rand = settings.rand.takeIf { rand(it) is Checked.Ok } ?: d.rand,
                randRange = settings.randRange.takeIf { randRange(it) is Checked.Ok } ?: d.randRange,
                delay = delay,
            )
        return (noise(random) as Checked.Ok).value
    }

    private fun Checked<XrayRange>.requireFrom(
        min: Int,
        problem: ObfuscationProblem,
    ): Checked<XrayRange> =
        when (this) {
            is Checked.Invalid -> this
            is Checked.Ok -> if (value.from < min) Checked.Invalid(problem) else this
        }

    private fun Checked<XrayRange>.requireAtMost(max: Int): Checked<XrayRange> =
        when (this) {
            is Checked.Invalid -> this
            is Checked.Ok -> if (value.to > max) Checked.Invalid(ObfuscationProblem.OutOfBounds) else this
        }

    private inline fun <T, R> Checked<T>.map(transform: (T) -> R): Checked<R> =
        when (this) {
            is Checked.Invalid -> this
            is Checked.Ok -> Checked.Ok(transform(value))
        }

    private inline fun <T, R> Checked<T>.flatMap(transform: (T) -> Checked<R>): Checked<R> =
        when (this) {
            is Checked.Invalid -> this
            is Checked.Ok -> transform(value)
        }
}
