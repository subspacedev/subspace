// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("TooManyFunctions", "ReturnCount")

package space.getsub.core.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import space.getsub.core.model.Hysteria2Congestion
import space.getsub.core.model.Hysteria2Obfuscation
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Tls
import space.getsub.core.model.PinResult
import space.getsub.core.model.PortHopping
import space.getsub.core.model.Profile
import space.getsub.core.model.normalisePin

private const val SCHEME_SEPARATOR = "://"
private const val DEFAULT_PORT = 443
private val DEFAULT_ALPN = listOf("h3")

/** One validation step: a value, or the typed failure that ends the parse. */
internal sealed interface Step<out T> {
    data class Ok<T>(
        val value: T,
    ) : Step<T>

    data class Fail(
        val reason: ParseFailureReason,
        val detail: FailureDetail,
    ) : Step<Nothing>
}

internal fun uriFail(detail: FailureDetail) = Step.Fail(ParseFailureReason.MalformedUri, detail)

private data class Authority(
    val password: String,
    val host: String,
    val portText: String,
    val query: Map<String, String>,
    val fragment: String,
)

private data class Hops(
    val port: Int,
    val ranges: List<IntRange>?,
)

/**
 * Parse a `hysteria2://auth@host[:ports]/?params#name` (or `hy2://`) share link.
 *
 * Grammar notes (Hysteria URI scheme; libXray v26.10.10 `share/hysteria2.go` is the prior art):
 * - The authority port is a list, `443`, `20000-30000` or `443,20000-30000`; more than one entry, or
 *   a range, means port hopping and the outbound port is the first port. `ports` / `mport` in the
 *   query give the same list for an authority that is a single port; combining them with a
 *   multi-port authority, or giving both, is a failure.
 * - `hop-interval` is whole seconds, at least 5 when hopping; without hopping it is ignored.
 * - `obfs` is `salamander`, `gecko` (needs `obfs-min-packet-size` / `obfs-max-packet-size`, the
 *   names Mihomo uses) or `none`; the password is at least 4 characters.
 * - `up` / `down` become Brutal bandwidths, see [normaliseBandwidth]. A bare number is Mbps.
 * - `pinSHA256` is one or more comma-separated pins; `insecure=1` only records the request.
 * - `fm=` is Xray `finalmask` JSON (see [readHysteria2Finalmask]) and wins over the query for
 *   whatever it sets. `fp` is ignored: the hysteria transport has no fingerprint.
 * - At most one value per query key.
 *
 * Failures name only the field and reason, never a value (ARCHITECTURE.md §5.6).
 */
internal fun parseHysteria2Link(
    raw: String,
    index: Int,
): LinkResult =
    when (val step = buildOutbound(raw)) {
        is Step.Fail -> LinkResult.Bad(parseFailure(index, step.reason, step.detail))
        is Step.Ok -> {
            val outbound = step.value
            LinkResult.Ok(
                Profile(
                    id = profileId("hysteria2", outbound.address, outbound.port, outbound.password),
                    name = nameOf(raw).ifBlank { outbound.address },
                    outbound = outbound,
                ),
            )
        }
    }

private fun nameOf(raw: String): String {
    val hash = raw.indexOf('#')
    return if (hash >= 0) percentDecode(raw.substring(hash + 1)) else ""
}

@Suppress("ReturnCount")
private fun buildOutbound(raw: String): Step<Hysteria2Outbound> {
    val authority = splitLink(raw)
    if (authority is Step.Fail) return authority
    val parts = (authority as Step.Ok).value
    if (parts.password.isEmpty()) {
        return Step.Fail(ParseFailureReason.MissingCredential, FailureDetail.Missing(DetailField.Password))
    }
    val query = parts.query
    val hops = resolveHops(parts)
    if (hops is Step.Fail) return hops
    hops as Step.Ok
    val queryHop = queryHopping(query, hops.value)
    val obfs = queryObfuscation(query)
    val congestion = queryCongestion(query)
    val tls = buildTls(query, parts.host)
    val fm = readFm(query)
    val failed = listOf(queryHop, obfs, congestion, tls, fm).firstOrNull { it is Step.Fail }
    if (failed is Step.Fail) return failed

    val mask = (fm as Step.Ok).value
    val hopping = mask?.hopping ?: (queryHop as Step.Ok).value
    return Step.Ok(
        Hysteria2Outbound(
            address = parts.host,
            port = hops.value.port,
            password = parts.password,
            tls = (tls as Step.Ok).value,
            hopping = hopping,
            obfuscation = mask?.obfuscation ?: (obfs as Step.Ok).value,
            congestion = mask?.congestion ?: (congestion as Step.Ok).value,
            notConnectableReason = mask?.notConnectableReason,
        ),
    )
}

@Suppress("ReturnCount", "CyclomaticComplexMethod")
private fun splitLink(raw: String): Step<Authority> {
    val text = raw.trim()
    val schemeEnd = text.indexOf(SCHEME_SEPARATOR)
    if (schemeEnd <= 0) return uriFail(FailureDetail.Malformed(DetailField.Uri))
    val rest = text.substring(schemeEnd + SCHEME_SEPARATOR.length)
    val hash = rest.indexOf('#')
    val beforeFragment = if (hash >= 0) rest.substring(0, hash) else rest
    val fragment = if (hash >= 0) percentDecode(rest.substring(hash + 1)) else ""
    val authorityEnd = beforeFragment.indexOfAny(charArrayOf('/', '?')).takeIf { it >= 0 } ?: beforeFragment.length
    val authority = beforeFragment.substring(0, authorityEnd)
    val afterAuthority = beforeFragment.substring(authorityEnd)
    val queryStart = afterAuthority.indexOf('?')
    val path = if (queryStart >= 0) afterAuthority.substring(0, queryStart) else afterAuthority
    if (path != "" && path != "/") return uriFail(FailureDetail.Malformed(DetailField.Uri))

    val query = if (queryStart >= 0) readQuery(afterAuthority.substring(queryStart + 1)) else emptyMap()
    if (query == null) return uriFail(FailureDetail.Malformed(DetailField.Uri))

    val at = authority.lastIndexOf('@')
    val password = if (at >= 0) percentDecode(authority.substring(0, at)) else ""
    val hostPort = authority.substring(at + 1)
    val hostAndPort = splitHostPort(hostPort) ?: return uriFail(FailureDetail.Malformed(DetailField.Address))
    return Step.Ok(Authority(password, hostAndPort.first, hostAndPort.second, query, fragment))
}

/** Null when a key repeats: a link that says two things about one setting is ambiguous. */
private fun readQuery(rawQuery: String): Map<String, String>? {
    val result = LinkedHashMap<String, String>()
    for (pair in rawQuery.split('&')) {
        val equals = pair.indexOf('=')
        if (equals <= 0) continue
        val key = percentDecode(pair.substring(0, equals))
        if (result.put(key, percentDecode(pair.substring(equals + 1))) != null) return null
    }
    return result
}

@Suppress("ReturnCount")
private fun splitHostPort(hostPort: String): Pair<String, String>? {
    if (hostPort.startsWith('[')) {
        val close = hostPort.indexOf(']')
        if (close <= 1) return null
        val tail = hostPort.substring(close + 1)
        if (tail.isNotEmpty() && !tail.startsWith(':')) return null
        return hostPort.substring(1, close) to (if (tail.isEmpty()) DEFAULT_PORT.toString() else tail.substring(1))
    }
    val colon = hostPort.indexOf(':')
    val host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
    if (host.isEmpty()) return null
    return host to (if (colon >= 0) hostPort.substring(colon + 1) else DEFAULT_PORT.toString())
}

private fun portFail(portText: String): Step.Fail {
    val single = portText.toIntOrNull()
    val detail = if (single != null) validatePort(single) else null
    return Step.Fail(ParseFailureReason.InvalidPort, detail ?: FailureDetail.Malformed(DetailField.Port))
}

/** The authority's port list, then `ports` / `mport`, into the outbound port and any hop ranges. */
@Suppress("ReturnCount")
private fun resolveHops(parts: Authority): Step<Hops> {
    var ranges = parsePortList(parts.portText) ?: return portFail(parts.portText)
    val query = parts.query
    val override = listOf("ports", "mport").mapNotNull { key -> query[key]?.let { key to it } }
    if (override.isNotEmpty()) {
        val authorityIsSingle = ranges.size == 1 && ranges[0].first == ranges[0].last
        if (!authorityIsSingle ||
            override.size > 1
        ) {
            return Step.Fail(ParseFailureReason.InvalidPort, FailureDetail.Malformed(DetailField.Port))
        }
        ranges = parsePortList(override[0].second) ?: return portFail(override[0].second)
    }
    val hops = ranges.size > 1 || ranges[0].first != ranges[0].last
    return Step.Ok(Hops(ranges[0].first, if (hops) ranges else null))
}

private fun queryHopping(
    query: Map<String, String>,
    hops: Hops,
): Step<PortHopping?> {
    // Without hopping, hop-interval is a harmless extra (panels emit it as a default): ignored.
    val ranges = hops.ranges ?: return Step.Ok(null)
    val text = query["hop-interval"]?.takeIf { it.isNotBlank() }
    val interval =
        if (text == null) {
            DEFAULT_HOP_INTERVAL
        } else {
            text.takeIf { it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it >= MIN_HOP_INTERVAL }
                ?: return uriFail(FailureDetail.Malformed(DetailField.HopInterval))
        }
    return Step.Ok(PortHopping(ranges, interval))
}

@Suppress("ReturnCount")
internal fun queryObfuscation(query: Map<String, String>): Step<Hysteria2Obfuscation> {
    val kind = query["obfs"]?.lowercase().orEmpty()
    val password = query["obfs-password"].orEmpty()
    if (kind.isEmpty() || kind == "none") {
        return if (password.isEmpty()) {
            Step.Ok(Hysteria2Obfuscation.None)
        } else {
            uriFail(FailureDetail.Malformed(DetailField.Obfuscation))
        }
    }
    if (kind != "salamander" && kind != "gecko") return uriFail(FailureDetail.Unsupported(DetailField.Obfuscation))
    if (password.isEmpty()) {
        return Step.Fail(ParseFailureReason.MissingCredential, FailureDetail.Missing(DetailField.Password))
    }
    if (password.length < MIN_OBFS_PASSWORD) return uriFail(FailureDetail.Malformed(DetailField.Obfuscation))
    if (kind == "salamander") return Step.Ok(Hysteria2Obfuscation.Salamander(password))
    val min = query["obfs-min-packet-size"]?.toIntOrNull()
    val max = query["obfs-max-packet-size"]?.toIntOrNull()
    val sane = min != null && max != null && min > 0 && min <= max
    return if (sane && max <= MAX_PACKET_SIZE) {
        Step.Ok(Hysteria2Obfuscation.Gecko(password, min, max))
    } else {
        uriFail(FailureDetail.Malformed(DetailField.Obfuscation))
    }
}

internal fun queryCongestion(query: Map<String, String>): Step<Hysteria2Congestion> {
    val up = query["up"]?.takeIf { it.isNotBlank() }
    val down = query["down"]?.takeIf { it.isNotBlank() }
    if (up == null && down == null) return Step.Ok(Hysteria2Congestion.Default)
    // libXray reads the query with Go's url.ParseQuery, where a bare `+` is a space.
    val upText = up?.let { normaliseBandwidth(it.replace('+', ' '), bareIsMbps = true) ?: return bandwidthFail() }
    val downText = down?.let { normaliseBandwidth(it.replace('+', ' '), bareIsMbps = true) ?: return bandwidthFail() }
    return Step.Ok(Hysteria2Congestion.Brutal(upText.orEmpty(), downText.orEmpty()))
}

private fun bandwidthFail() = uriFail(FailureDetail.Malformed(DetailField.Bandwidth))

@Suppress("ReturnCount")
internal fun buildTls(
    query: Map<String, String>,
    host: String,
): Step<Hysteria2Tls> {
    val security = query["security"]?.lowercase()
    if (!security.isNullOrEmpty() && security != "tls") return uriFail(FailureDetail.Unsupported(DetailField.Security))
    val pins = mutableListOf<String>()
    for (item in query["pinSHA256"]
        .orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }) {
        when (val pin = normalisePin(item)) {
            is PinResult.Ok -> if (pin.hex !in pins) pins += pin.hex
            PinResult.Invalid -> return uriFail(FailureDetail.Malformed(DetailField.Pin))
        }
    }
    val alpn =
        query["alpn"]
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    val insecure = query["insecure"]?.lowercase() in setOf("1", "true")
    return Step.Ok(
        Hysteria2Tls(
            serverName = query["sni"]?.takeIf { it.isNotBlank() } ?: host,
            alpn = alpn.ifEmpty { DEFAULT_ALPN },
            pins = pins,
            skipChecksRequested = insecure,
        ),
    )
}

/** `fm=` JSON, or null when absent/blank. Anything that is not a JSON object is a typed failure. */
private fun readFm(query: Map<String, String>): Step<Hysteria2Finalmask?> {
    val text = query["fm"]?.takeIf { it.isNotBlank() } ?: return Step.Ok(null)
    val element = runCatching { Json.parseToJsonElement(text) }.getOrNull()
    return if (element is JsonObject) {
        Step.Ok(readHysteria2Finalmask(element))
    } else {
        Step.Fail(ParseFailureReason.MalformedJson, FailureDetail.Malformed(DetailField.JsonBody))
    }
}
