// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("ReturnCount")

package space.getsub.core.parser

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.WireGuardOutbound
import space.getsub.core.model.WireGuardRules
import space.getsub.core.model.WireGuardUnsupported

private fun clashFail(
    index: Int,
    reason: ParseFailureReason,
    detail: FailureDetail,
): LinkResult = LinkResult.Bad(parseFailure(index, reason, detail))

/** A scalar's text, or a list's scalars joined by commas, trimmed (D2-R7); null when absent. */
private fun YamlMap.listOrScalar(key: String): List<String>? =
    when (val node = node(key)) {
        is YamlList -> node.items.mapNotNull { (it as? YamlScalar)?.content?.trim() }
        is YamlScalar -> node.content.split(',').map { it.trim() }
        else -> null
    }?.filter { it.isNotEmpty() }

/** The `peers:` entries; null when the key is absent, an empty list when it is present but unreadable. */
private fun clashPeers(proxy: YamlMap): List<YamlMap>? {
    val nodes = (proxy.node("peers") as? YamlList)?.items ?: return null
    val maps = nodes.filterIsInstance<YamlMap>()
    return if (maps.size == nodes.size) maps else emptyList()
}

/**
 * A Clash / Mihomo `type: wireguard` entry (field names: research "Verified for D2" (b)).
 *
 * Single-peer form (`server`, `port`, `public-key`, ... on the entry) or a `peers:` list. One peer is read;
 * several import as a row that is not connectable ([WireGuardUnsupported.MultiplePeers]) with the first
 * peer's data, so the row is visible rather than silently shortened. `ip` / `ipv6` are the local
 * addresses; `reserved` is a list or a base64 string. Keys are trimmed, then checked with
 * [WireGuardRules.keyValid] (D2-R7). `udp`, `dns` and `remote-dns-resolve` are ignored; `amnezia-wg-option`
 * makes the entry an unsupported-protocol failure (D2-R13: AmneziaWG is future work).
 *
 * Runs before the generic server/port check in `ClashYaml.kt`: the `peers:` form has no top-level server.
 * Failures name a field, never a value (ARCHITECTURE.md §5.6).
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun wireGuardFromClash(
    proxy: YamlMap,
    index: Int,
): LinkResult {
    // Ruling D2-R13: AmneziaWG is future work (M9 covers Xray-core only); skip it like any unsupported type.
    if (proxy.node("amnezia-wg-option") != null) {
        return clashFail(index, ParseFailureReason.UnknownScheme, FailureDetail.Unsupported(DetailField.Scheme))
    }
    val peers = clashPeers(proxy)
    if (peers != null && peers.isEmpty()) {
        return clashFail(index, ParseFailureReason.MalformedYaml, FailureDetail.Malformed(DetailField.YamlBody))
    }
    val peer: YamlMap = if (peers == null) proxy else peers[0]
    val server = peer.text("server")
    if (server == null) {
        return clashFail(index, ParseFailureReason.MalformedYaml, FailureDetail.Missing(DetailField.Address))
    }
    val port = peer.text("port")?.trim()?.toIntOrNull()
    if (port == null) {
        return clashFail(index, ParseFailureReason.InvalidPort, FailureDetail.Malformed(DetailField.Port))
    }
    validatePort(port)?.let { return clashFail(index, ParseFailureReason.InvalidPort, it) }

    val secret = proxy.text("private-key")?.trim().orEmpty()
    val publicKey = peer.text("public-key")?.trim().orEmpty()
    val psk = peer.text("pre-shared-key")?.trim().orEmpty()
    keyProblem(secret, DetailField.SecretKey)?.let { return clashFail(index, ParseFailureReason.MissingCredential, it) }
    keyProblem(publicKey, DetailField.PublicKey)?.let {
        return clashFail(index, ParseFailureReason.MissingCredential, it)
    }
    if (psk.isNotEmpty() && !WireGuardRules.keyValid(psk)) {
        return clashFail(index, ParseFailureReason.MissingCredential, FailureDetail.Malformed(DetailField.Credential))
    }

    val addresses = listOfNotNull(proxy.text("ip")?.trim(), proxy.text("ipv6")?.trim())
    if (addresses.isEmpty()) {
        return clashFail(index, ParseFailureReason.MalformedYaml, FailureDetail.Missing(DetailField.LocalAddress))
    }
    if (addresses.any { !validLocalAddress(it) }) {
        return clashFail(index, ParseFailureReason.MalformedYaml, FailureDetail.Malformed(DetailField.LocalAddress))
    }
    val reserved =
        WireGuardRules.parseReserved(peer.listOrScalar("reserved")?.joinToString(",").orEmpty())
            ?: return clashFail(index, ParseFailureReason.MalformedYaml, FailureDetail.Malformed(DetailField.Reserved))
    val mtu = boundedInt(proxy.text("mtu"), MIN_WG_MTU, MAX_WG_MTU, DetailField.Mtu)
    if (mtu is Step.Fail) return LinkResult.Bad(parseFailure(index, mtu.reason, mtu.detail))
    val keepAlive = boundedInt(proxy.text("persistent-keepalive"), 0, MAX_KEEPALIVE, DetailField.Keepalive)
    if (keepAlive is Step.Fail) return LinkResult.Bad(parseFailure(index, keepAlive.reason, keepAlive.detail))

    val outbound =
        WireGuardOutbound(
            address = server,
            port = port,
            secretKey = secret,
            localAddresses = addresses,
            peerPublicKey = publicKey,
            preSharedKey = psk.ifEmpty { null },
            mtu = (mtu as Step.Ok).value ?: WireGuardRules.DEFAULT_MTU,
            reserved = reserved,
            keepAlive = (keepAlive as Step.Ok).value?.takeIf { it > 0 },
            allowedIps = peer.listOrScalar("allowed-ips").orEmpty().ifEmpty { listOf("0.0.0.0/0", "::/0") },
            notConnectableReason = unsupportedReason(peers),
        )
    val name = proxy.text("name") ?: server
    return LinkResult.Ok(Profile(profileId("wireguard", server, port, secret), name, outbound))
}

private fun unsupportedReason(peers: List<YamlMap>?): WireGuardUnsupported? =
    if ((peers?.size ?: 0) > 1) WireGuardUnsupported.MultiplePeers else null

private fun keyProblem(
    key: String,
    field: DetailField,
): FailureDetail? =
    when {
        key.isEmpty() -> FailureDetail.Missing(field)
        !WireGuardRules.keyValid(key) -> FailureDetail.Malformed(field)
        else -> null
    }

/**
 * A Clash / Mihomo `type: http` entry: `server`, `port`, `username`, `password`, `tls`, `sni`,
 * `skip-cert-verify`. `fingerprint` is Mihomo's certificate pin, not a TLS client fingerprint, and
 * is ignored (ruling D2-R4: pins stay Hysteria2-only); `headers` are ignored.
 */
internal fun httpFromClash(
    proxy: YamlMap,
    common: ClashCommon,
): LinkResult {
    val username = proxy.text("username")
    val password = proxy.text("password")
    val security =
        if (proxy.text("tls") == "true") {
            Security.Tls(serverName = common.sni, fingerprint = "chrome", allowInsecure = common.allowInsecure)
        } else {
            Security.None
        }
    val outbound = HttpOutbound(common.server, common.port, username, password, security)
    val id = profileId("http", common.server, common.port, credentialMaterial(username, password))
    return LinkResult.Ok(Profile(id, common.name, outbound))
}
