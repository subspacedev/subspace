// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.getsub.core.model.isKcpNetwork

/** A key's value, with JSON null read as absent, as Xray reads it. */
internal fun JsonObject.present(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

/** The outbound's `streamSettings` object, if it is one. */
internal fun JsonObject.stream(): JsonObject? = this["streamSettings"] as? JsonObject

/** The tag in `streamSettings.sockopt.dialerProxy`, if any. */
internal fun JsonObject.dialerProxy(): String? = (stream()?.get("sockopt") as? JsonObject)?.string("dialerProxy")

/** The outbound's `protocol` string. */
internal fun JsonObject.protocol(): String? = string("protocol")

/** Which socket kinds an outbound dials, for mask applicability (mirrors `ObfuscationApplicability`). */
internal enum class Dials { UdpOnly, Both }

internal fun JsonObject.dials(): Dials {
    val network = stream()?.string("network").orEmpty()
    val protocol = protocol()
    return when {
        protocol == "hysteria" || network.equals("hysteria", ignoreCase = true) -> Dials.UdpOnly
        protocol == "wireguard" || isKcpNetwork(network) -> Dials.UdpOnly
        else -> Dials.Both
    }
}

/**
 * Whether the user's noise runs on this outbound: the raw-config twin of
 * `ObfuscationApplicability.noiseApplies`, kept equal to it by `ObfuscationDetectionTest`'s parity test.
 */
internal fun JsonObject.carriesNoise(): Boolean {
    val network = stream()?.string("network").orEmpty()
    return when (protocol()) {
        "hysteria", "wireguard" -> true
        "vless", "vmess", "trojan" -> isKcpNetwork(network)
        else -> network.equals("hysteria", ignoreCase = true)
    }
}

/** A string-typed JSON primitive under [key], else null. */
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Whether mux.cool can serve this outbound (M9-E research R2.6): the raw-config twin of
 * `MuxApplicability.applies`, kept equal to it by `MuxDetectionTest`'s parity test.
 *
 * One rule the typed table cannot state: a Shadowsocks outbound on XHTTP is not eligible either, as
 * for every other protocol. The typed model gives Shadowsocks no stream, so the two never disagree on
 * a row both can describe.
 */
internal fun JsonObject.carriesMux(): Boolean {
    val network = stream()?.string("network").orEmpty().lowercase()
    if (network == "xhttp" || network == "splithttp") return false
    return when (protocol()) {
        "vless" -> vlessFlows().all { it.isBlank() }
        "vmess", "trojan", "shadowsocks" -> true
        else -> false
    }
}

/**
 * Every `flow` a VLESS outbound names, in either settings shape Xray-core v26.10.10 reads
 * (`infra/conf/vless.go:245-300`): flat `settings.flow`, or `settings.vnext[].users[].flow`.
 * Both are read, so a flow in either one makes the outbound ineligible.
 */
private fun JsonObject.vlessFlows(): List<String> {
    val settings = this["settings"] as? JsonObject ?: return emptyList()
    val users =
        (settings["vnext"] as? JsonArray)
            .orEmpty()
            .flatMap { ((it as? JsonObject)?.get("users") as? JsonArray).orEmpty() }
            .mapNotNull { (it as? JsonObject)?.string("flow") }
    return listOfNotNull(settings.string("flow")) + users
}
