// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("TooManyFunctions", "ReturnCount", "MagicNumber")

package space.getsub.core.parser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.getsub.core.model.Hysteria2Congestion
import space.getsub.core.model.Hysteria2Obfuscation
import space.getsub.core.model.Hysteria2Unsupported
import space.getsub.core.model.PortHopping
import space.getsub.core.model.normaliseBbrProfile

/**
 * What a Hysteria2 outbound's Xray `finalmask` object says, as typed model values.
 *
 * Each of [obfuscation], [hopping] and [congestion] is null when the finalmask does not set
 * that thing, so a caller can let a lower-priority source (a share link's query string) fill
 * it in. [notConnectableReason] is non-null when the finalmask holds something that changes
 * the wire which this build cannot write; the caller keeps the profile but must mark it not
 * connectable, because dropping the content would produce a server that connects to nothing.
 */
internal data class Hysteria2Finalmask(
    val obfuscation: Hysteria2Obfuscation? = null,
    val hopping: PortHopping? = null,
    val congestion: Hysteria2Congestion? = null,
    val notConnectableReason: Hysteria2Unsupported? = null,
)

internal const val MAX_PACKET_SIZE = 2048
internal const val MIN_OBFS_PASSWORD = 4
internal const val DEFAULT_HOP_INTERVAL = 30
internal const val MIN_HOP_INTERVAL = 5
private const val MIN_BRUTAL_BYTES_PER_SECOND = 65_536.0
private const val BITS_PER_BYTE = 8
private const val KILO = 1024.0
private const val MAX_PORT_NUMBER = 65_535
private const val MAX_PORT_DIGITS = 5
private const val MAX_INT_DIGITS = 9
private const val UDPHOP_MODE = "intervalLocal,intervalRemote"

/**
 * Reads the Hysteria2-relevant parts of an Xray `finalmask` object (a `fm=` query value on a
 * share link, or `streamSettings.finalmask` in raw Xray JSON). Never throws; unusable content
 * becomes [Hysteria2Finalmask.notConnectableReason].
 *
 * Understood: `udp[]` masks `salamander` (a `packetSize` makes it Gecko) and `udphop` (the
 * v26.10.10 shape); `quicParams.congestion` (`""`, `bbr` + `bbrProfile`, `brutal` +
 * `brutalUp`/`brutalDown`); and the pre-v26.10 `quicParams.udpHop {ports, interval}`, which
 * is translated into the same hopping value. Any other `udp[]` mask type, a malformed
 * understood mask, a congestion value outside that set, or `brutalDisableLossCompensation`
 * set to true is not representable. Everything else (`tcp`, debug, windows, keepalive, ...)
 * is ignored as harmless to the wire.
 */
@Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
internal fun readHysteria2Finalmask(finalmask: JsonObject): Hysteria2Finalmask {
    var obfuscation: Hysteria2Obfuscation? = null
    var hopping: PortHopping? = null
    var reason: Hysteria2Unsupported? = null

    for (mask in (finalmask["udp"] as? JsonArray).orEmpty()) {
        val entry = mask as? JsonObject
        val settings = entry?.get("settings") as? JsonObject
        when (entry?.stringValue("type")?.lowercase()) {
            "salamander" -> {
                val read = readSalamander(settings)
                if (read == null || obfuscation != null) {
                    reason = reason ?: Hysteria2Unsupported.UnknownMask
                } else {
                    obfuscation = read
                }
            }

            "udphop" -> {
                val read = readUdpHop(settings)
                if (read == null || hopping != null) {
                    reason = reason ?: Hysteria2Unsupported.UnknownMask
                } else {
                    hopping = read
                }
            }

            else -> reason = reason ?: Hysteria2Unsupported.UnknownMask
        }
    }

    val quic = finalmask.objectValue("quicParams")
    val congestion = quic?.let { readCongestion(it) }
    if (congestion == CongestionRead.Unsupported) reason = reason ?: Hysteria2Unsupported.UnknownCongestion
    val oldHop = quic?.objectValue("udpHop")
    if (oldHop != null && hopping == null) {
        hopping = readOldUdpHop(oldHop)
        if (hopping == null) reason = reason ?: Hysteria2Unsupported.UnknownMask
    }
    return Hysteria2Finalmask(
        obfuscation = obfuscation,
        hopping = hopping,
        congestion = (congestion as? CongestionRead.Value)?.congestion,
        notConnectableReason = reason,
    )
}

private sealed interface CongestionRead {
    data class Value(
        val congestion: Hysteria2Congestion,
    ) : CongestionRead

    data object Unsupported : CongestionRead
}

private fun readCongestion(quic: JsonObject): CongestionRead? {
    val lossCompensationOff = quic["brutalDisableLossCompensation"]
    if (lossCompensationOff != null && quic.booleanValue("brutalDisableLossCompensation") != false) {
        return CongestionRead.Unsupported
    }
    val name = (quic["congestion"] as? JsonPrimitive)
    return when {
        quic["congestion"] == null || name?.stringContent()?.isEmpty() == true -> null
        name?.stringContent() == null -> CongestionRead.Unsupported
        else -> readNamedCongestion(name.content, quic)
    }
}

private fun readNamedCongestion(
    name: String,
    quic: JsonObject,
): CongestionRead =
    when (name.lowercase()) {
        "bbr" -> {
            // A profile the core does not know fails its Build (`unknown bbr profile`), so the row
            // imports but is not connectable, like any other congestion this build cannot write.
            val raw = if (quic["bbrProfile"] == null) "" else quic.stringValue("bbrProfile")
            val profile = raw?.let(::normaliseBbrProfile)
            if (profile == null) CongestionRead.Unsupported else CongestionRead.Value(Hysteria2Congestion.Bbr(profile))
        }
        "brutal" -> {
            val up = bandwidthField(quic, "brutalUp")
            val down = bandwidthField(quic, "brutalDown")
            if (up == null || down == null) {
                CongestionRead.Unsupported
            } else {
                CongestionRead.Value(Hysteria2Congestion.Brutal(up, down))
            }
        }

        else -> CongestionRead.Unsupported
    }

/** Absent is `""` (unset); a present value must be a valid Bandwidth string (a JSON number is not). */
private fun bandwidthField(
    quic: JsonObject,
    key: String,
): String? {
    val element = quic[key] ?: return ""
    val text = (element as? JsonPrimitive)?.stringContent() ?: return null
    return if (text.isEmpty()) "" else normaliseBandwidth(text, bareIsMbps = false)
}

private fun readSalamander(settings: JsonObject?): Hysteria2Obfuscation? {
    val password = settings?.stringValue("password")?.takeIf { it.length >= MIN_OBFS_PASSWORD } ?: return null
    val size = settings["packetSize"] ?: return Hysteria2Obfuscation.Salamander(password)
    val (from, to) = readIntRange(size) ?: return null
    return when {
        to <= 0 -> Hysteria2Obfuscation.Salamander(password)
        from <= 0 || to > MAX_PACKET_SIZE || from > to -> null
        else -> Hysteria2Obfuscation.Gecko(password, from, to)
    }
}

private fun readUdpHop(settings: JsonObject?): PortHopping? {
    val mode = settings?.stringValue("mode")
    val noRemoteIps = (settings?.get("remoteIPs") as? JsonArray).orEmpty().isEmpty()
    val ports = settings?.get("remotePorts")?.let { readPortList(it) }
    val interval = readInterval(settings?.get("interval"))
    val valid = mode == UDPHOP_MODE && noRemoteIps
    return if (valid && ports != null && interval != null) PortHopping(ports, interval) else null
}

private fun readOldUdpHop(hop: JsonObject): PortHopping? {
    val ports = hop["ports"]?.let { readPortList(it) }
    val interval = readInterval(hop["interval"])
    return if (ports != null && interval != null) PortHopping(ports, interval) else null
}

/** Absent means the default; a range with different ends has no single-value model, so null. */
private fun readInterval(element: JsonElement?): Int? {
    if (element == null) return DEFAULT_HOP_INTERVAL
    val (from, to) = readIntRange(element) ?: return null
    return if (from == to) from.takeIf { it >= MIN_HOP_INTERVAL } else null
}

private fun readPortList(element: JsonElement): List<IntRange>? =
    (element as? JsonPrimitive)
        ?.content
        ?.let(::parsePortList)

/** An Xray `Int32Range`: a JSON number, a `"a-b"` string, or (leniently) `{from, to}`. */
private fun readIntRange(element: JsonElement): Pair<Int, Int>? =
    when (element) {
        is JsonObject -> {
            val from = element.portValue("from")
            val to = element.portValue("to")
            if (from != null && to != null) from to to else null
        }

        is JsonPrimitive -> parseIntRange(element.content)
        else -> null
    }

private fun parseIntRange(text: String): Pair<Int, Int>? {
    val parts = text.trim().split('-')
    val numbers = parts.map { digitsOrNull(it, MAX_INT_DIGITS) }
    return when {
        numbers.size == 1 && numbers[0] != null -> numbers[0]!! to numbers[0]!!
        numbers.size == 2 && numbers[0] != null && numbers[1] != null ->
            minOf(numbers[0]!!, numbers[1]!!) to
                maxOf(numbers[0]!!, numbers[1]!!)
        else -> null
    }
}

/**
 * Parses a port list such as `443`, `20000-30000` or `443,20000-30000` into ranges. Every port is
 * 1..65535 and every range ascending; anything else, including an empty list, is null.
 */
internal fun parsePortList(text: String): List<IntRange>? {
    val ranges =
        text.split(',').map { item ->
            val bounds = item.trim().split('-')
            val numbers = bounds.map { digitsOrNull(it, MAX_PORT_DIGITS) }
            when {
                numbers.size == 1 && numbers[0] != null -> numbers[0]!!..numbers[0]!!
                numbers.size == 2 && numbers[0] != null && numbers[1] != null -> numbers[0]!!..numbers[1]!!
                else -> null
            }
        }
    return if (ranges.all { it != null && it.first in 1..MAX_PORT_NUMBER && it.last in it.first..MAX_PORT_NUMBER }) {
        ranges.filterNotNull()
    } else {
        null
    }
}

private val BANDWIDTH_SHAPE = Regex("""^(\d+(?:\.\d+)?)\s*([a-z]*)$""")
private val BANDWIDTH_UNITS =
    mapOf(
        "" to 1.0,
        "b" to 1.0,
        "bps" to 1.0,
        "k" to KILO,
        "kb" to KILO,
        "kbps" to KILO,
        "m" to KILO * KILO,
        "mb" to KILO * KILO,
        "mbps" to KILO * KILO,
        "g" to KILO * KILO * KILO,
        "gb" to KILO * KILO * KILO,
        "gbps" to KILO * KILO * KILO,
        "t" to KILO * KILO * KILO * KILO,
        "tb" to KILO * KILO * KILO * KILO,
        "tbps" to KILO * KILO * KILO * KILO,
    )

/**
 * Validates a Hysteria2 bandwidth against Xray's `Bandwidth` grammar (v26.10.10
 * `transport_method.go`): a decimal number, optional spaces, then a unit from
 * `b|bps|k|kb|kbps|m|mb|mbps|g|gb|gbps|t|tb|tbps` (case-insensitive). Xray treats every unit,
 * the bare and `b`/`bps` forms included, as bits per second and divides by 8
 * (`infra/conf/transport_method.go:706-744`, `Bandwidth.Bps`), so a bare `70000` is 8750 bytes/s.
 * The value must be at least 65536 bytes/second (Xray's Brutal floor).
 *
 * Returns the text to store: trimmed as given, except that a bare number becomes `"<n> mbps"`
 * when [bareIsMbps] (the Hysteria URI scheme's meaning of `up=100`). Null when invalid.
 */
internal fun normaliseBandwidth(
    text: String,
    bareIsMbps: Boolean,
): String? {
    val trimmed = text.trim()
    val match = BANDWIDTH_SHAPE.matchEntire(trimmed.lowercase()) ?: return null
    val unit = match.groupValues[2]
    val multiplier = BANDWIDTH_UNITS[unit] ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    val mbpsMultiplier = KILO * KILO
    val effective = if (bareIsMbps && unit.isEmpty()) mbpsMultiplier else multiplier
    val bytesPerSecond = value * effective / BITS_PER_BYTE
    return when {
        bytesPerSecond < MIN_BRUTAL_BYTES_PER_SECOND -> null
        bareIsMbps && unit.isEmpty() -> "${match.groupValues[1]} mbps"
        else -> trimmed
    }
}

private fun digitsOrNull(
    text: String,
    maxLength: Int,
): Int? = if (text.length in 1..maxLength && text.all(Char::isDigit)) text.toInt() else null
