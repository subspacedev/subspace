// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("ReturnCount")

package space.getsub.core.parser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.Profile
import space.getsub.core.model.WireGuardOutbound
import space.getsub.core.model.WireGuardRules
import space.getsub.core.model.WireGuardUnsupported

private const val MAX_PORT_TEXT = 5
private val DEFAULT_WG_ADDRESSES = listOf("10.0.0.1", "fd59:7153:2388:b5fd:0000:0000:0000:0001")

private fun rawFail(
    index: Int,
    reason: ParseFailureReason,
    detail: FailureDetail,
): LinkResult = LinkResult.Bad(parseFailure(index, reason, detail))

private fun badAddress(index: Int): LinkResult =
    rawFail(index, ParseFailureReason.MalformedJson, FailureDetail.Malformed(DetailField.LocalAddress))

private fun JsonObject.rawName(
    remarks: String?,
    fallback: String,
): String {
    val tag = stringValue("tag")?.takeIf { it.isNotBlank() }
    return if (remarks != null) remarks else tag ?: fallback
}

/** `host:port` or `[v6]:port`. Null when it is neither. */
private fun splitEndpoint(endpoint: String): Pair<String, Int>? {
    val text = endpoint.trim()
    val split = if (text.startsWith('[')) splitBracketed(text) else splitPlain(text)
    val port = split?.second?.takeIf { it.length <= MAX_PORT_TEXT }?.toIntOrNull()
    return if (split == null || port == null || split.first.isBlank()) null else split.first to port
}

private fun splitBracketed(text: String): Pair<String, String>? {
    val close = text.indexOf(']')
    val valid = close > 1 && text.substring(close + 1).startsWith(':')
    return if (valid) text.substring(1, close) to text.substring(close + 2) else null
}

private fun splitPlain(text: String): Pair<String, String>? {
    val colon = text.lastIndexOf(':')
    val valid = colon > 0 && text.indexOf(':') == colon
    return if (valid) text.substring(0, colon) to text.substring(colon + 1) else null
}

/**
 * A raw Xray `protocol: "wireguard"` outbound as a typed [WireGuardOutbound].
 *
 * `settings {secretKey, address[], peers[0] {publicKey, preSharedKey, endpoint "host:port", keepAlive,
 * allowedIPs}, mtu, reserved}`. Several peers, an unusable key or a bad `reserved` keep the row but make
 * it not connectable; a missing secret key, public key, endpoint, a non-IP `address` item or an
 * unreadable shape is a typed failure. The row's [Profile.rawJson] stays the connect source; this is
 * the projection that gives it latency handling and details. Never puts a key in a failure.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun parseWireGuardOutbound(
    outbound: JsonObject,
    index: Int,
    remarks: String?,
    elementText: String,
): LinkResult {
    val settings =
        outbound.objectValue("settings")
            ?: return rawFail(index, ParseFailureReason.MalformedJson, FailureDetail.Malformed(DetailField.JsonBody))
    val peers = settings["peers"] as? JsonArray
    val peer =
        peers?.firstOrNull() as? JsonObject
            ?: return rawFail(index, ParseFailureReason.MalformedJson, FailureDetail.Missing(DetailField.PublicKey))
    val (address, port) =
        peer.stringValue("endpoint")?.let(::splitEndpoint)
            ?: return rawFail(index, ParseFailureReason.MalformedJson, FailureDetail.Missing(DetailField.Address))
    validatePort(port)?.let { return rawFail(index, ParseFailureReason.InvalidPort, it) }

    val secret = settings.stringValue("secretKey")?.trim().orEmpty()
    val publicKey = peer.stringValue("publicKey")?.trim().orEmpty()
    if (secret.isEmpty()) {
        return rawFail(index, ParseFailureReason.MissingCredential, FailureDetail.Missing(DetailField.SecretKey))
    }
    if (publicKey.isEmpty()) {
        return rawFail(index, ParseFailureReason.MissingCredential, FailureDetail.Missing(DetailField.PublicKey))
    }
    val psk = peer.stringValue("preSharedKey")?.trim().orEmpty()

    val addresses =
        when (val element = settings["address"]) {
            null -> DEFAULT_WG_ADDRESSES
            is JsonArray -> element.map { (it as? JsonPrimitive)?.stringContent()?.trim().orEmpty() }
            else -> return badAddress(index)
        }
    if (addresses.isEmpty() || addresses.any { !validLocalAddress(it) }) {
        return badAddress(index)
    }

    val reservedText =
        when (val element = settings["reserved"]) {
            null -> ""
            is JsonArray -> element.joinToString(",") { (it as? JsonPrimitive)?.content.orEmpty() }
            // Xray's `[]byte` takes a JSON array or base64; a comma list as a string is not base64.
            is JsonPrimitive -> if (element.isString && ',' !in element.content) element.content else "?"
            else -> "?"
        }
    val reserved = WireGuardRules.parseReserved(reservedText)
    // An explicit [] means no allowed IPs (nothing routes): project it as empty, not as the default.
    val allowed =
        (peer["allowedIPs"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.stringContent()?.trim()?.takeIf { v -> v.isNotEmpty() } }
    val mtuElement = settings["mtu"]
    val mtuGiven = settings.intValue("mtu")
    val mtuBad = mtuElement != null && (mtuGiven == null || mtuGiven != 0 && mtuGiven !in MIN_WG_MTU..MAX_WG_MTU)
    val mtu = mtuGiven?.takeIf { it != 0 && !mtuBad } ?: WireGuardRules.DEFAULT_MTU
    val keyBad =
        listOf(secret, publicKey, psk).any { it.isNotEmpty() && !WireGuardRules.keyValid(it) }
    val reason =
        when {
            (peers.size) > 1 -> WireGuardUnsupported.MultiplePeers
            keyBad -> WireGuardUnsupported.InvalidKey
            reserved == null -> WireGuardUnsupported.InvalidReserved
            mtuBad -> WireGuardUnsupported.InvalidMtu
            else -> null
        }
    val result =
        WireGuardOutbound(
            address = address,
            port = port,
            secretKey = secret,
            localAddresses = addresses,
            peerPublicKey = publicKey,
            preSharedKey = psk.ifEmpty { null },
            mtu = mtu,
            reserved = reserved.orEmpty(),
            keepAlive = peer.intValue("keepAlive")?.takeIf { it > 0 },
            allowedIps = allowed ?: listOf("0.0.0.0/0", "::/0"),
            notConnectableReason = reason,
        )
    return LinkResult.Ok(
        Profile(
            profileId("wireguard", address, port, secret),
            outbound.rawName(remarks, address),
            result,
            rawJson = elementText,
        ),
    )
}

/** The server object and its optional user: `settings` itself (flat form) or the only `servers[]` entry. */
private fun httpServerAndUser(settings: JsonObject): Pair<JsonObject, JsonObject?>? {
    val servers = settings["servers"] as? JsonArray
    if (servers == null || settings["address"] != null) return settings to settings
    val only = servers.singleOrNull() as? JsonObject ?: return null
    val users = only["users"] as? JsonArray
    return if ((users?.size ?: 0) > 1) null else only to (users?.firstOrNull() as? JsonObject)
}

/**
 * A raw Xray `protocol: "http"` outbound as a typed [HttpOutbound]: the flat `settings {address, port,
 * user, pass}` form or the legacy `servers[]` form (exactly one server, at most one user, like the core).
 * TLS comes from `streamSettings` exactly as for VLESS; `allowInsecure` is recorded, never emitted.
 */
internal fun parseHttpOutbound(
    outbound: JsonObject,
    index: Int,
    remarks: String?,
    elementText: String,
): LinkResult {
    val settings =
        outbound.objectValue("settings")
            ?: return rawFail(index, ParseFailureReason.MalformedJson, FailureDetail.Malformed(DetailField.JsonBody))
    val pair =
        httpServerAndUser(settings)
            ?: return rawFail(index, ParseFailureReason.MalformedJson, FailureDetail.Malformed(DetailField.JsonBody))
    val server = pair.first
    val user = pair.second
    val address =
        server.stringValue("address")?.takeIf { it.isNotBlank() }
            ?: return rawFail(index, ParseFailureReason.MalformedJson, FailureDetail.Missing(DetailField.Address))
    val port =
        server.portValue("port")
            ?: return rawFail(index, ParseFailureReason.InvalidPort, FailureDetail.Malformed(DetailField.Port))
    validatePort(port)?.let { return rawFail(index, ParseFailureReason.InvalidPort, it) }
    val username = user?.stringValue("user")
    val password = user?.stringValue("pass")
    val streamSettings = outbound["streamSettings"] as? JsonObject
    val security =
        when (val parsed = parseSecurity(streamSettings, address)) {
            is SecurityParse.Bad -> return rawFail(index, parsed.reason, parsed.detail)
            is SecurityParse.Ok -> parsed.security
        }
    val result = HttpOutbound(address, port, username, password, security)
    return LinkResult.Ok(
        Profile(
            profileId("http", address, port, credentialMaterial(username, password)),
            outbound.rawName(remarks, address),
            result,
            rawJson = elementText,
        ),
    )
}
