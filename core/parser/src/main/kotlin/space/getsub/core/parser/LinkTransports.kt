// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("ReturnCount")

package space.getsub.core.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import space.getsub.core.model.KcpHeader
import space.getsub.core.model.KcpHeaderResult
import space.getsub.core.model.KcpObfuscation
import space.getsub.core.model.KcpRules
import space.getsub.core.model.KcpUnsupported
import space.getsub.core.model.TransportOptions

private const val MIN_KCP_MTU = 21
private const val MIN_KCP_TTI = 10
private const val MAX_KCP_TTI = 1000

internal fun isKcpNetwork(network: String): Boolean = network.lowercase().let { it == "kcp" || it == "mkcp" }

internal fun isHttpUpgradeNetwork(network: String): Boolean = network.equals("httpupgrade", ignoreCase = true)

/**
 * mKCP and HTTPUpgrade from a vless/trojan link's query, or null when [network] is neither (the caller's
 * own reader handles it). A bad `mtu`/`tti` or an unreadable `fm=` is a typed failure.
 *
 * `fm=` is read only for kcp (the other transports ignore it, as before) and wins over `seed`/`headerType`.
 */
internal fun queryKcpOrHttpUpgrade(
    query: Map<String, String>,
    network: String,
): Step<TransportOptions>? =
    when {
        isKcpNetwork(network) -> kcpFromQuery(query)
        isHttpUpgradeNetwork(network) -> Step.Ok(httpUpgrade(query["host"], query["path"]))
        else -> null
    }

internal fun httpUpgrade(
    host: String?,
    path: String?,
): TransportOptions.HttpUpgrade {
    val cleanPath = path?.takeIf { it.isNotBlank() } ?: "/"
    return TransportOptions.HttpUpgrade(host.orEmpty().trim(), cleanPath)
}

private fun kcpFromQuery(query: Map<String, String>): Step<TransportOptions> {
    val mtu = boundedInt(query["mtu"], MIN_KCP_MTU, Int.MAX_VALUE, DetailField.Mtu)
    if (mtu is Step.Fail) return mtu
    val tti = boundedInt(query["tti"], MIN_KCP_TTI, MAX_KCP_TTI, DetailField.Tti)
    if (tti is Step.Fail) return tti
    val fm = readKcpFm(query["fm"])
    if (fm is Step.Fail) return fm
    val fromFm = (fm as Step.Ok).value
    val obfuscation = fromFm ?: classicObfuscation(query["seed"], query["headerType"])
    return Step.Ok(TransportOptions.Kcp((mtu as Step.Ok).value, (tti as Step.Ok).value, obfuscation))
}

internal fun classicObfuscation(
    seed: String?,
    headerType: String?,
    dnsDomain: String? = null,
): KcpObfuscation {
    val s = seed?.takeIf { it.isNotEmpty() }
    return when (val h = KcpRules.headerFromLinkName(headerType)) {
        KcpHeaderResult.None -> KcpObfuscation(s, null, false)
        // Ruling D2-R11: a raw legacy `header {type: dns, domain}` (Xray's pre-finalmask DNSAuthenticator, which
        // defaulted the domain to www.baidu.com) has no field here; written without it the core would put the
        // default domain on the wire, so the row is not connectable rather than silently different.
        is KcpHeaderResult.Ok -> {
            val domainLost = h.header == KcpHeader.DNS && !dnsDomain.isNullOrEmpty()
            val reason = KcpUnsupported.DnsHeaderDomain.takeIf { domainLost }
            KcpObfuscation(s, h.header, legacyOriginal = s == null, unsupported = reason)
        }
        KcpHeaderResult.Unsupported ->
            KcpObfuscation(
                s,
                null,
                false,
                if (headerType?.trim().equals("http", ignoreCase = true)) {
                    KcpUnsupported.HttpHeader
                } else {
                    KcpUnsupported.UnknownHeader
                },
            )
    }
}

private fun readKcpFm(text: String?): Step<KcpObfuscation?> {
    if (text.isNullOrBlank()) return Step.Ok(null)
    val element = runCatching { Json.parseToJsonElement(text) }.getOrNull()
    return if (element is JsonObject) {
        Step.Ok(readKcpFinalmask(element).obfuscation)
    } else {
        Step.Fail(ParseFailureReason.MalformedJson, FailureDetail.Malformed(DetailField.JsonBody))
    }
}

/** Absent or blank is null; otherwise an int in [min]..[max] or a typed failure carrying only numbers. */
internal fun boundedInt(
    text: String?,
    min: Int,
    max: Int,
    field: DetailField,
): Step<Int?> {
    val t = text?.trim().orEmpty()
    val n = t.toIntOrNull()
    return when {
        t.isEmpty() -> Step.Ok(null)
        n == null -> uriFail(FailureDetail.Malformed(field))
        n in min..max -> Step.Ok(n)
        else -> uriFail(FailureDetail.Range(field, min, max, n))
    }
}
