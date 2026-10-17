// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("ReturnCount")

package space.getsub.core.parser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.getsub.core.model.Hysteria2Congestion
import space.getsub.core.model.Hysteria2Obfuscation
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Rules
import space.getsub.core.model.Hysteria2Tls
import space.getsub.core.model.PinResult
import space.getsub.core.model.Profile
import space.getsub.core.model.normalisePin

private const val HYSTERIA_VERSION = "2"

private fun rawBad(
    index: Int,
    reason: ParseFailureReason,
    detail: FailureDetail,
): LinkResult = LinkResult.Bad(parseFailure(index, reason, detail))

/**
 * A raw Xray `protocol: "hysteria"` outbound (v26.10.10 layout) as a typed [Hysteria2Outbound].
 *
 * `settings {version, address, port}`; `streamSettings {network: "hysteria", hysteriaSettings
 * {version, auth}, security: "tls", tlsSettings, finalmask}`. Only version 2 is understood.
 * `finalmask` goes through [readHysteria2Finalmask], the same reader `fm=` uses. `fingerprint` is
 * ignored (the transport has none); `allowInsecure: true` is only recorded, since
 * `analysePassthrough` already refuses such a row. The projection is what gives a raw row
 * latency, address pinning and details; the row's [Profile.rawJson] stays the connect source.
 */
@Suppress("CyclomaticComplexMethod")
internal fun parseHysteriaOutbound(
    outbound: JsonObject,
    index: Int,
    remarks: String?,
    elementText: String,
): LinkResult {
    val settings = outbound.objectValue("settings")
    val stream = outbound.objectValue("streamSettings")
    val hysteria = stream?.objectValue("hysteriaSettings")
    val address =
        settings?.stringValue("address")?.takeIf { it.isNotBlank() }
            ?: return rawBad(index, ParseFailureReason.MalformedJson, FailureDetail.Missing(DetailField.Address))
    val port =
        settings.portValue("port")
            ?: return rawBad(index, ParseFailureReason.InvalidPort, FailureDetail.Malformed(DetailField.Port))
    val portProblem = validatePort(port)
    if (portProblem != null) return rawBad(index, ParseFailureReason.InvalidPort, portProblem)

    val versions = listOfNotNull(settings["version"], hysteria?.get("version")).map { (it as? JsonPrimitive)?.content }
    val network = stream?.stringValue("network")
    if (versions.any { it != HYSTERIA_VERSION } || (network != null && network != "hysteria")) {
        return rawBad(index, ParseFailureReason.UnknownScheme, FailureDetail.Unsupported(DetailField.Scheme))
    }
    val password =
        hysteria?.stringValue("auth")?.takeIf { it.isNotEmpty() }
            ?: return rawBad(
                index,
                ParseFailureReason.MissingCredential,
                FailureDetail.Missing(DetailField.Password),
            )
    if (stream.stringValue("security")?.lowercase() != "tls") {
        return rawBad(index, ParseFailureReason.MalformedJson, FailureDetail.Unsupported(DetailField.Security))
    }
    val tls =
        rawTls(stream.objectValue("tlsSettings"), address)
            ?: return rawBad(index, ParseFailureReason.MalformedJson, FailureDetail.Malformed(DetailField.Pin))
    val mask = readHysteria2Finalmask(stream.objectValue("finalmask") ?: JsonObject(emptyMap()))
    val result =
        Hysteria2Outbound(
            address = address,
            port = port,
            password = password,
            tls = tls,
            hopping = mask.hopping,
            obfuscation = mask.obfuscation ?: Hysteria2Obfuscation.None,
            congestion = mask.congestion ?: Hysteria2Congestion.Default,
            notConnectableReason = mask.notConnectableReason,
        )
    val tagName = outbound.stringValue("tag")?.takeIf { it.isNotBlank() }
    val name = if (remarks != null) remarks else tagName ?: address
    return LinkResult.Ok(Profile(profileId("hysteria2", address, port, password), name, result, rawJson = elementText))
}

/** Null when a pin is invalid. Pins may be one comma-separated string or an array of them. */
private fun rawTls(
    tls: JsonObject?,
    address: String,
): Hysteria2Tls? {
    val pinValues =
        when (val element = tls?.get("pinnedPeerCertSha256")) {
            is JsonArray -> element.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
            is JsonPrimitive -> if (element.isString) listOf(element.content) else return null
            else -> emptyList()
        }
    val pins = mutableListOf<String>()
    for (item in pinValues.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }) {
        when (val pin = normalisePin(item)) {
            is PinResult.Ok -> if (pin.hex !in pins) pins += pin.hex
            PinResult.Invalid -> return null
        }
    }
    val alpn = (tls?.get("alpn") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.stringContent() }
    return Hysteria2Tls(
        serverName = tls?.stringValue("serverName")?.takeIf { it.isNotBlank() } ?: address,
        alpn = alpn.ifEmpty { Hysteria2Rules.DEFAULT_ALPN },
        pins = pins,
        skipChecksRequested = tls?.booleanValue("allowInsecure") == true,
    )
}
