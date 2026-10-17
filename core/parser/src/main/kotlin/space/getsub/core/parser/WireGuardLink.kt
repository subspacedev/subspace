// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("ReturnCount")

package space.getsub.core.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import space.getsub.core.model.Profile
import space.getsub.core.model.WireGuardOutbound
import space.getsub.core.model.WireGuardRules
import space.getsub.core.model.WireGuardUnsupported

private const val SCHEME_SEPARATOR = "://"
private const val MAX_PORT_TEXT = 5
private const val MIN_WG_MTU = 1
private const val MAX_WG_MTU = 65_535
private const val MAX_KEEPALIVE = 65_535
private val SECRET_KEY_NAMES = listOf("privatekey", "pk")
private val PEER_KEY_NAMES = listOf("publickey", "peerpublickey", "pub", "peerpub", "peerpk")
private val PSK_NAMES = listOf("presharedkey", "psk")
private val ADDRESS_NAMES = listOf("address", "ip", "localaddress")
private val ADDRESS_SHAPE = Regex("""^[0-9A-Fa-f:.]+(/[0-9]{1,3})?$""")

private data class Pieces(
    val userInfo: String,
    val host: String,
    val portText: String,
    val query: Map<String, String>,
    val fragment: String,
)

/**
 * Parse a `wireguard://` (v2rayN) or `wg://` (Hiddify) link; the two grammars are one parser (ruling D2-R5).
 *
 * `wireguard://<secretKey>@host:port?publickey=&presharedkey=&address=&reserved=&mtu=&keepalive=#name`, or
 * `wg://host:port/?pk=&peer_pk=&...`: query names are matched case-insensitively with `_`/`-` removed, so
 * the aliases of both dialects resolve. The secret key is the userinfo or, failing that, `privatekey`/`pk`.
 * Values are trimmed (D2-R7). `dns` is ignored. `fm=` is ignored unless it carries masks, in which case the
 * row imports not connectable: WireGuard noise is a user setting, not a link field.
 *
 * Failures name a field, never a value (ARCHITECTURE.md §5.6).
 */
internal fun parseWireGuardLink(
    raw: String,
    index: Int,
): LinkResult =
    when (val step = buildWireGuard(raw)) {
        is Step.Fail -> LinkResult.Bad(parseFailure(index, step.reason, step.detail))
        is Step.Ok -> {
            val (outbound, name) = step.value
            LinkResult.Ok(
                Profile(
                    id = profileId("wireguard", outbound.address, outbound.port, outbound.secretKey),
                    name = name.ifBlank { outbound.address },
                    outbound = outbound,
                ),
            )
        }
    }

@Suppress("CyclomaticComplexMethod")
private fun buildWireGuard(raw: String): Step<Pair<WireGuardOutbound, String>> {
    val pieces = splitPieces(raw) ?: return uriFail(FailureDetail.Malformed(DetailField.Uri))
    val port = pieces.portText.takeIf { it.length <= MAX_PORT_TEXT }?.toIntOrNull()
    if (pieces.host.isEmpty() || port == null) {
        return Step.Fail(ParseFailureReason.InvalidPort, FailureDetail.Malformed(DetailField.Port))
    }
    validatePort(port)?.let { return Step.Fail(ParseFailureReason.InvalidPort, it) }

    val query = pieces.query
    val secret = pieces.userInfo.trim().ifEmpty { first(query, SECRET_KEY_NAMES) }
    val peer = first(query, PEER_KEY_NAMES)
    val psk = first(query, PSK_NAMES)
    keyFailure(secret, DetailField.SecretKey, ParseFailureReason.MissingCredential)?.let { return it }
    keyFailure(peer, DetailField.PublicKey, ParseFailureReason.MissingCredential)?.let { return it }
    if (psk.isNotEmpty() && !WireGuardRules.keyValid(psk)) {
        return Step.Fail(ParseFailureReason.MissingCredential, FailureDetail.Malformed(DetailField.Credential))
    }

    val addresses = first(query, ADDRESS_NAMES).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (addresses.isEmpty()) return uriFail(FailureDetail.Missing(DetailField.LocalAddress))
    if (addresses.any { !ADDRESS_SHAPE.matches(it) }) return uriFail(FailureDetail.Malformed(DetailField.LocalAddress))

    val reserved =
        WireGuardRules.parseReserved(query["reserved"].orEmpty())
            ?: return uriFail(FailureDetail.Malformed(DetailField.Reserved))
    val mtu = boundedInt(query["mtu"], MIN_WG_MTU, MAX_WG_MTU, DetailField.Mtu)
    if (mtu is Step.Fail) return mtu
    val keepAlive = boundedInt(query["keepalive"], 0, MAX_KEEPALIVE, DetailField.Keepalive)
    if (keepAlive is Step.Fail) return keepAlive
    val allowed =
        query["allowedips"]
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    val mask = fmCarriesMasks(query["fm"])
    if (mask is Step.Fail) return mask

    val outbound =
        WireGuardOutbound(
            address = pieces.host,
            port = port,
            secretKey = secret,
            localAddresses = addresses,
            peerPublicKey = peer,
            preSharedKey = psk.ifEmpty { null },
            mtu = (mtu as Step.Ok).value ?: WireGuardRules.DEFAULT_MTU,
            reserved = reserved,
            keepAlive = (keepAlive as Step.Ok).value?.takeIf { it > 0 },
            allowedIps = allowed.ifEmpty { listOf("0.0.0.0/0", "::/0") },
            notConnectableReason = if ((mask as Step.Ok).value) WireGuardUnsupported.UnsupportedMask else null,
        )
    return Step.Ok(outbound to pieces.fragment)
}

private fun keyFailure(
    key: String,
    field: DetailField,
    missing: ParseFailureReason,
): Step.Fail? =
    when {
        key.isEmpty() -> Step.Fail(missing, FailureDetail.Missing(field))
        !WireGuardRules.keyValid(key) -> Step.Fail(missing, FailureDetail.Malformed(field))
        else -> null
    }

private fun first(
    query: Map<String, String>,
    names: List<String>,
): String = names.firstNotNullOfOrNull { query[it]?.takeIf { v -> v.isNotEmpty() } }.orEmpty()

/** True when `fm=` holds any tcp/udp mask. Absent or blank is false; unreadable JSON is a typed failure. */
private fun fmCarriesMasks(text: String?): Step<Boolean> {
    if (text.isNullOrBlank()) return Step.Ok(false)
    val element =
        runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return Step.Fail(ParseFailureReason.MalformedJson, FailureDetail.Malformed(DetailField.JsonBody))
    return Step.Ok(listOf("udp", "tcp").any { (element[it] as? JsonArray)?.isNotEmpty() == true })
}

private fun splitPieces(raw: String): Pieces? {
    val text = raw.trim()
    val schemeEnd = text.indexOf(SCHEME_SEPARATOR)
    if (schemeEnd <= 0) return null
    val rest = text.substring(schemeEnd + SCHEME_SEPARATOR.length)
    val hash = rest.indexOf('#')
    val beforeFragment = if (hash >= 0) rest.substring(0, hash) else rest
    val fragment = if (hash >= 0) percentDecode(rest.substring(hash + 1)).trim() else ""
    val q = beforeFragment.indexOf('?')
    val authority = if (q >= 0) beforeFragment.substring(0, q) else beforeFragment
    val query = if (q >= 0) readNormalisedQuery(beforeFragment.substring(q + 1)) else emptyMap()

    val at = authority.lastIndexOf('@')
    val userInfo = if (at >= 0) percentDecode(authority.substring(0, at)) else ""
    val hostPort = authority.substring(at + 1).substringBefore('/')
    val (host, portText) = splitHostPort(hostPort) ?: return null
    return Pieces(userInfo, host, portText, query, fragment)
}

private fun splitHostPort(hostPort: String): Pair<String, String>? {
    if (hostPort.startsWith('[')) {
        val close = hostPort.indexOf(']')
        if (close <= 1) return null
        val tail = hostPort.substring(close + 1)
        if (!tail.startsWith(':')) return null
        return hostPort.substring(1, close) to tail.substring(1)
    }
    val colon = hostPort.indexOf(':')
    if (colon < 0) return hostPort.takeIf { it.isNotEmpty() }?.let { it to "" }
    if (colon == 0 || colon != hostPort.lastIndexOf(':')) return null
    return hostPort.substring(0, colon) to hostPort.substring(colon + 1)
}

/** Keys lower-cased with `_`/`-` removed; values percent-decoded (a literal `+` stays) and trimmed. */
private fun readNormalisedQuery(rawQuery: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (pair in rawQuery.split('&')) {
        val equals = pair.indexOf('=')
        if (equals <= 0) continue
        val key = percentDecode(pair.substring(0, equals)).lowercase().filter { it != '_' && it != '-' }
        result.putIfAbsent(key, percentDecode(pair.substring(equals + 1)).trim())
    }
    return result
}
