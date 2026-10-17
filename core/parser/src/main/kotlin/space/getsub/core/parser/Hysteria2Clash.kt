// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("ReturnCount")

package space.getsub.core.parser

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.PortHopping
import space.getsub.core.model.Profile

/**
 * A Clash / Mihomo `type: hysteria2` entry.
 *
 * Mihomo's field names are the Hysteria URI scheme's, so the entry is laid out as the equivalent
 * share-link query and goes through the same obfuscation, congestion and TLS rules as
 * [parseHysteria2Link]: `obfs`, `obfs-password`, `obfs-min-packet-size` / `obfs-max-packet-size`,
 * `up` / `down` (a bare number is Mbps), `sni`, `alpn`, `skip-cert-verify`, and `fingerprint`
 * (Mihomo's certificate SHA-256 pin, not a TLS client fingerprint). `ports` is the hop list and
 * `hop-interval` its period. `client-fingerprint` is ignored: the hysteria transport has none.
 */
internal fun hysteria2FromClash(
    proxy: YamlMap,
    common: ClashCommon,
): LinkResult {
    val index = common.index
    val server = common.server
    val port = common.port
    val password =
        proxy.text("password")
            ?: return clashBad(
                index,
                ParseFailureReason.MissingCredential,
                FailureDetail.Missing(DetailField.Password),
            )
    val query = clashQuery(proxy, common.sni, common.allowInsecure)
    val hopping = clashHopping(proxy)
    val obfs = queryObfuscation(query)
    val congestion = queryCongestion(query)
    val tls = buildTls(query, server)
    val failed = listOf(hopping, obfs, congestion, tls).firstOrNull { it is Step.Fail }
    if (failed is Step.Fail) return LinkResult.Bad(parseFailure(index, failed.reason, failed.detail))
    val outbound =
        Hysteria2Outbound(
            address = server,
            port = port,
            password = password,
            tls = (tls as Step.Ok).value,
            hopping = (hopping as Step.Ok).value,
            obfuscation = (obfs as Step.Ok).value,
            congestion = (congestion as Step.Ok).value,
        )
    return LinkResult.Ok(Profile(profileId("hysteria2", server, port, password), common.name, outbound))
}

private fun clashBad(
    index: Int,
    reason: ParseFailureReason,
    detail: FailureDetail,
): LinkResult = LinkResult.Bad(parseFailure(index, reason, detail))

/** The entry's TLS / obfuscation / bandwidth fields as the share-link query that means the same. */
private fun clashQuery(
    proxy: YamlMap,
    sni: String,
    skipCertVerify: Boolean,
): Map<String, String> {
    val alpn =
        when (val node = proxy.node("alpn")) {
            is YamlList -> node.items.mapNotNull { (it as? YamlScalar)?.content }
            is YamlScalar -> listOf(node.content)
            else -> emptyList()
        }
    val entries =
        mapOf(
            "sni" to sni,
            "alpn" to alpn.joinToString(","),
            "pinSHA256" to proxy.text("fingerprint"),
            "insecure" to if (skipCertVerify) "1" else null,
            "obfs" to proxy.text("obfs"),
            "obfs-password" to proxy.text("obfs-password"),
            "obfs-min-packet-size" to proxy.text("obfs-min-packet-size"),
            "obfs-max-packet-size" to proxy.text("obfs-max-packet-size"),
            "up" to proxy.text("up"),
            "down" to proxy.text("down"),
        )
    return entries.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
}

/** `ports` makes a hop list when it names more than one port; `hop-interval` only matters then. */
private fun clashHopping(proxy: YamlMap): Step<PortHopping?> {
    val portsText = proxy.text("ports") ?: return Step.Ok(null)
    val ranges =
        parsePortList(portsText) ?: return Step.Fail(
            ParseFailureReason.InvalidPort,
            FailureDetail.Malformed(DetailField.Port),
        )
    // Without hopping, hop-interval is a harmless extra (panels emit it as a default): ignored.
    if (ranges.size == 1 && ranges[0].first == ranges[0].last) return Step.Ok(null)
    val intervalText = proxy.text("hop-interval")
    val interval =
        if (intervalText == null) {
            DEFAULT_HOP_INTERVAL
        } else {
            intervalText.takeIf { it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it >= MIN_HOP_INTERVAL }
                ?: return uriFail(FailureDetail.Malformed(DetailField.HopInterval))
        }
    return Step.Ok(PortHopping(ranges, interval))
}
