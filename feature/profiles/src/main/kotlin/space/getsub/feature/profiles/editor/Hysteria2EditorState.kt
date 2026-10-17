// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.editor

import space.getsub.core.model.BBR_PROFILES
import space.getsub.core.model.Hysteria2Congestion
import space.getsub.core.model.Hysteria2Obfuscation
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Tls
import space.getsub.core.model.Hysteria2Unsupported
import space.getsub.core.model.PinResult
import space.getsub.core.model.PortHopping
import space.getsub.core.model.normaliseBbrProfile
import space.getsub.core.model.normalisePin
import space.getsub.core.parser.Hysteria2Input

/** The Hysteria2 form fields that can fail validation. Errors name the field, never its content. */
internal enum class Hysteria2Field {
    Pins,
    HopPorts,
    HopInterval,
    ObfsPassword,
    PacketRange,
    BbrProfile,
    BrutalUp,
    BrutalDown,
}

internal enum class Hysteria2ObfsKind { None, Salamander, Gecko }

internal enum class Hysteria2CongestionKind { Default, Bbr, Brutal }

/** The port a new Hysteria2 server starts with. */
private const val DEFAULT_PORT = 443

private val PIN_SEPARATORS = Regex("[,\\s]+")

/**
 * Everything the Hysteria2 form edits that is not already a shared [EditorState] field (address, port
 * and the password live in `address`, `port` and `primaryCredential`).
 *
 * All text, like the rest of [EditorState], so a half-typed value survives. ARCHITECTURE.md §5.6:
 * [pinsText], [obfsPassword] and nothing else here may reach a log or an error string; [toString]
 * redacts them and [Hysteria2Field] errors carry no content.
 *
 * [alpn] is not editable (the core overwrites it with `h3`, constraints R5) but travels through a
 * save unchanged. [skipChecksRequested] and [notConnectableReason] are facts about the source: the
 * editor reports them and carries them, but cannot clear them (the unknown `fm=` content that caused
 * the reason is not in the model).
 */
@Suppress("TooManyFunctions") // One small check or mapping per form group; splitting would only rename them.
internal data class Hysteria2Draft(
    val serverName: String = "",
    val alpn: List<String> = listOf("h3"),
    val pinsText: String = "",
    val skipChecksRequested: Boolean = false,
    val hopPorts: String = "",
    val hopInterval: String = Hysteria2Input.DEFAULT_HOP_INTERVAL.toString(),
    val obfsKind: Hysteria2ObfsKind = Hysteria2ObfsKind.None,
    val obfsPassword: String = "",
    val minPacket: String = "",
    val maxPacket: String = "",
    val congestionKind: Hysteria2CongestionKind = Hysteria2CongestionKind.Default,
    val bbrProfile: String = "",
    val brutalUp: String = "",
    val brutalDown: String = "",
    val notConnectableReason: Hysteria2Unsupported? = null,
) {
    /** The pins as typed, one token each; blank tokens dropped. */
    val pinTokens: List<String> get() = pinsText.split(PIN_SEPARATORS).filter { it.isNotBlank() }

    /** The source asked to skip certificate checks and no pin is entered: they stay on and it will fail. */
    val checksStayOnWithoutPin: Boolean get() = skipChecksRequested && pinTokens.isEmpty()

    val hops: Boolean get() = hopPorts.isNotBlank()

    /** The fields that are wrong, by name. Empty means [toOutbound] is exact. */
    fun validate(): Set<Hysteria2Field> =
        buildSet {
            if (pinTokens.any { normalisePin(it) !is PinResult.Ok }) add(Hysteria2Field.Pins)
            validateHopping()
            validateObfuscation()
            validateCongestion()
        }

    private fun MutableSet<Hysteria2Field>.validateHopping() {
        // R9: without hopping the interval is ignored, so it cannot be wrong.
        if (!hops) return
        if (Hysteria2Input.portList(hopPorts) == null) add(Hysteria2Field.HopPorts)
        val interval = hopInterval.trim().toIntOrNull()
        if (interval == null || interval < Hysteria2Input.MIN_HOP_INTERVAL) add(Hysteria2Field.HopInterval)
    }

    private fun MutableSet<Hysteria2Field>.validateObfuscation() {
        if (obfsKind == Hysteria2ObfsKind.None) return
        if (obfsPassword.length < Hysteria2Input.MIN_OBFS_PASSWORD) add(Hysteria2Field.ObfsPassword)
        if (obfsKind == Hysteria2ObfsKind.Gecko) {
            val min = minPacket.trim().toIntOrNull()
            val max = maxPacket.trim().toIntOrNull()
            val valid = min != null && max != null && min > 0 && min <= max && max <= Hysteria2Input.MAX_PACKET_SIZE
            if (!valid) add(Hysteria2Field.PacketRange)
        }
    }

    private fun MutableSet<Hysteria2Field>.validateCongestion() {
        when (congestionKind) {
            Hysteria2CongestionKind.Default -> Unit
            Hysteria2CongestionKind.Bbr -> if (normaliseBbrProfile(bbrProfile) == null) add(Hysteria2Field.BbrProfile)
            Hysteria2CongestionKind.Brutal -> {
                if (!bandwidthOk(brutalUp)) add(Hysteria2Field.BrutalUp)
                if (!bandwidthOk(brutalDown)) add(Hysteria2Field.BrutalDown)
            }
        }
    }

    /** Blank is "unset" (an imported link may leave either side out); anything typed must be valid. */
    private fun bandwidthOk(text: String): Boolean =
        text.isBlank() || Hysteria2Input.bandwidth(text, bareIsMbps = true) != null

    /** Total: text that [validate] would refuse falls back to empty/default, since save validates first. */
    fun toOutbound(
        address: String,
        port: Int,
        password: String,
    ): Hysteria2Outbound =
        Hysteria2Outbound(
            address = address,
            port = port,
            password = password,
            tls =
            Hysteria2Tls(
                serverName = serverName,
                alpn = alpn,
                pins = pinTokens.mapNotNull { (normalisePin(it) as? PinResult.Ok)?.hex },
                skipChecksRequested = skipChecksRequested,
            ),
            hopping = hopping(),
            obfuscation = obfuscation(),
            congestion = congestion(),
            notConnectableReason = notConnectableReason,
        )

    private fun hopping(): PortHopping? {
        val ports = if (hops) Hysteria2Input.portList(hopPorts) else null
        val interval = hopInterval.trim().toIntOrNull() ?: Hysteria2Input.DEFAULT_HOP_INTERVAL
        return ports?.let { PortHopping(it, interval) }
    }

    private fun obfuscation(): Hysteria2Obfuscation =
        when (obfsKind) {
            Hysteria2ObfsKind.None -> Hysteria2Obfuscation.None
            Hysteria2ObfsKind.Salamander -> Hysteria2Obfuscation.Salamander(obfsPassword)
            Hysteria2ObfsKind.Gecko ->
                Hysteria2Obfuscation.Gecko(
                    obfsPassword,
                    minPacket.trim().toIntOrNull() ?: 0,
                    maxPacket.trim().toIntOrNull() ?: 0,
                )
        }

    private fun congestion(): Hysteria2Congestion =
        when (congestionKind) {
            Hysteria2CongestionKind.Default -> Hysteria2Congestion.Default
            Hysteria2CongestionKind.Bbr -> Hysteria2Congestion.Bbr(normaliseBbrProfile(bbrProfile).orEmpty())
            Hysteria2CongestionKind.Brutal ->
                Hysteria2Congestion.Brutal(brutalBandwidth(brutalUp), brutalBandwidth(brutalDown))
        }

    private fun brutalBandwidth(text: String): String =
        if (text.isBlank()) "" else Hysteria2Input.bandwidth(text, bareIsMbps = true).orEmpty()

    override fun toString(): String =
        "Hysteria2Draft(pins=<redacted>, obfsKind=$obfsKind, congestionKind=$congestionKind, " +
            "hops=$hops, notConnectableReason=$notConnectableReason)"

    companion object {
        fun from(outbound: Hysteria2Outbound): Hysteria2Draft {
            val base =
                Hysteria2Draft(
                    serverName = outbound.tls.serverName,
                    alpn = outbound.tls.alpn,
                    pinsText = outbound.tls.pins.joinToString("\n"),
                    skipChecksRequested = outbound.tls.skipChecksRequested,
                    notConnectableReason = outbound.notConnectableReason,
                )
            return base
                .withHopping(outbound.hopping)
                .withObfuscation(outbound.obfuscation)
                .withCongestion(outbound.congestion)
        }

        /**
         * A stored bandwidth with no unit is bits per second (the core's reading), but a bare number typed
         * in the editor means Mbps. Showing it with its unit keeps an untouched value meaning what it did.
         */
        private fun displayBandwidth(stored: String): String =
            if (stored.isNotEmpty() && stored.all { it.isDigit() || it == '.' }) "$stored bps" else stored

        private fun IntRange.render(): String = if (first == last) "$first" else "$first-$last"

        private fun Hysteria2Draft.withHopping(hopping: PortHopping?): Hysteria2Draft =
            if (hopping == null) {
                this
            } else {
                copy(
                    hopPorts = hopping.ports.joinToString(",") { it.render() },
                    hopInterval = hopping.intervalSeconds.toString(),
                )
            }

        private fun Hysteria2Draft.withObfuscation(obfuscation: Hysteria2Obfuscation): Hysteria2Draft =
            when (obfuscation) {
                Hysteria2Obfuscation.None -> this
                is Hysteria2Obfuscation.Salamander ->
                    copy(obfsKind = Hysteria2ObfsKind.Salamander, obfsPassword = obfuscation.password)
                is Hysteria2Obfuscation.Gecko ->
                    copy(
                        obfsKind = Hysteria2ObfsKind.Gecko,
                        obfsPassword = obfuscation.password,
                        minPacket = obfuscation.minPacket.toString(),
                        maxPacket = obfuscation.maxPacket.toString(),
                    )
            }

        private fun Hysteria2Draft.withCongestion(congestion: Hysteria2Congestion): Hysteria2Draft =
            when (congestion) {
                Hysteria2Congestion.Default -> this
                is Hysteria2Congestion.Bbr ->
                    copy(congestionKind = Hysteria2CongestionKind.Bbr, bbrProfile = congestion.profile)
                is Hysteria2Congestion.Brutal ->
                    copy(
                        congestionKind = Hysteria2CongestionKind.Brutal,
                        brutalUp = displayBandwidth(congestion.up),
                        brutalDown = displayBandwidth(congestion.down),
                    )
            }
    }
}

/** The BBR profiles the dropdown offers, in the order the core documents them. */
internal val BBR_PROFILE_OPTIONS: List<String> = BBR_PROFILES.toList()

/**
 * What the protocol picker creates for "Hysteria2": TLS with `h3`, and no hopping, obfuscation or
 * congestion. The editor has no picker yet, so nothing in the app calls this; it is the one place the
 * defaults are written down for when one exists.
 */
internal fun newHysteria2Outbound(): Hysteria2Outbound =
    Hysteria2Outbound(
        address = "",
        port = DEFAULT_PORT,
        password = "",
        tls = Hysteria2Tls(serverName = "", alpn = listOf("h3")),
    )
