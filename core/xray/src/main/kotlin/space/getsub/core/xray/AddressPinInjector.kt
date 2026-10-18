// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pins a raw config's own server address to a pre-resolved IP (M9-C spec §3). Principle: after the
 * swap, every name Xray derives from the dial address is still the domain it was before, so only
 * empty fields are filled and a non-empty value is never changed. Outbounds that do not name the
 * domain are returned as the same instance.
 */
public object AddressPinInjector {
    private val LENIENT = Json { isLenient = true }
    private val ADDRESS_ARRAYS = listOf("vnext", "servers")

    public fun apply(
        outbounds: JsonArray,
        pin: ServerAddressPin?,
    ): JsonArray {
        if (pin == null) return outbounds
        return JsonArray(
            outbounds.map { element ->
                val outbound = element as? JsonObject
                if (outbound == null) element else rewrite(outbound, pin) ?: element
            },
        )
    }

    /**
     * True when any outbound's `vnext`/`servers` entry, or a Hysteria `settings.address`, names
     * [domain]. False on any parse error.
     */
    @Suppress("SwallowedException", "ReturnCount")
    public fun matches(
        rawJson: String,
        domain: String,
    ): Boolean {
        val root =
            try {
                LENIENT.parseToJsonElement(rawJson) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return false
        val outbounds = root["outbounds"] as? JsonArray ?: return false
        return outbounds.any { o ->
            val outbound = o as? JsonObject
            val settings = outbound?.get("settings") as? JsonObject
            settings != null &&
                (
                    ADDRESS_ARRAYS.any { key -> (settings[key] as? JsonArray)?.any { it.names(domain) } == true } ||
                        (outbound.hasFlatAddress() && settings.names(domain))
                    )
        }
    }

    private fun JsonElement.names(domain: String): Boolean {
        val address = (this as? JsonObject)?.get("address") as? JsonPrimitive ?: return false
        return address.isString && norm(address.content) == norm(domain)
    }

    /** The rewritten outbound, or null when nothing in it names the domain. */
    @Suppress("ReturnCount")
    private fun rewrite(
        outbound: JsonObject,
        pin: ServerAddressPin,
    ): JsonObject? {
        val settings = outbound["settings"] as? JsonObject ?: return null
        val newSettings = pinnedSettings(outbound, settings, pin) ?: return null
        val result = outbound.toMutableMap().apply { put("settings", newSettings) }
        val stream = outbound["streamSettings"]
        if (stream == null || stream is JsonNull || stream is JsonObject) {
            val filled = fill((stream as? JsonObject) ?: JsonObject(emptyMap()), pin.domain)
            if (filled != null) result["streamSettings"] = filled
        }
        return JsonObject(result)
    }

    /** [settings] with every address naming the pin's domain swapped for its IP, or null when none does. */
    private fun pinnedSettings(
        outbound: JsonObject,
        settings: JsonObject,
        pin: ServerAddressPin,
    ): JsonObject? {
        var matched = false
        val newSettings = settings.toMutableMap()
        for (key in ADDRESS_ARRAYS) {
            val array = settings[key] as? JsonArray ?: continue
            newSettings[key] =
                JsonArray(
                    array.map { entry ->
                        if (entry.names(pin.domain)) {
                            matched = true
                            JsonObject((entry as JsonObject) + ("address" to JsonPrimitive(pin.ip)))
                        } else {
                            entry
                        }
                    },
                )
        }
        if (outbound.hasFlatAddress() && settings.names(pin.domain)) {
            matched = true
            newSettings["address"] = JsonPrimitive(pin.ip)
        }
        return if (matched) JsonObject(newSettings) else null
    }

    /** The stream with empty address-derived fields set to [domain], or null when nothing changes. */
    private fun fill(
        stream: JsonObject,
        domain: String,
    ): JsonObject? {
        val security = stream.text("security")?.lowercase().orEmpty().ifEmpty { "none" }
        val network = stream.text("network")?.lowercase().orEmpty().ifEmpty { "tcp" }
        val edits = mutableMapOf<String, JsonElement>()

        fun set(
            settingsKey: String,
            fieldKey: String,
            wanted: (JsonObject) -> Boolean = { true },
        ) {
            filled(stream, settingsKey, fieldKey, domain, wanted)?.let { edits[settingsKey] = it }
        }

        when (security) {
            "tls" -> set("tlsSettings", "serverName")
            "reality" -> set("realitySettings", "serverName")
        }
        when (network) {
            "ws" -> set("wsSettings", "host") { security != "tls" && !it.hasHostHeader() }
            "httpupgrade" -> set("httpupgradeSettings", "host") { security != "tls" }
            "grpc" -> set("grpcSettings", "authority") { security == "none" }
            "xhttp", "splithttp" -> set("${network}Settings", "host") { security == "none" }
        }
        return if (edits.isEmpty()) null else JsonObject(stream + edits)
    }

    // The settings object with [fieldKey] set, or null when it must stay as written.
    private fun filled(
        stream: JsonObject,
        settingsKey: String,
        fieldKey: String,
        domain: String,
        wanted: (JsonObject) -> Boolean,
    ): JsonObject? {
        val obj =
            when (val current = stream[settingsKey]) {
                null, is JsonNull -> JsonObject(emptyMap())
                is JsonObject -> current
                else -> return null
            }
        return if (wanted(obj) && obj.isEmpty(fieldKey)) JsonObject(obj + (fieldKey to JsonPrimitive(domain))) else null
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    // JSON null and a blank string read as absent; any other type is not ours to overwrite.
    private fun JsonObject.isEmpty(key: String): Boolean =
        when (val v = this[key]) {
            null, is JsonNull -> true
            is JsonPrimitive -> v.isString && v.content.isBlank()
            else -> false
        }

    private fun JsonObject.hasHostHeader(): Boolean {
        val headers = this["headers"] as? JsonObject ?: return false
        return headers.keys.any { it.equals("host", ignoreCase = true) }
    }
}

/**
 * Protocols whose `settings` names the server directly, with no `vnext`/`servers` array:
 * `HysteriaClientConfig` (`infra/conf/hysteria.go`, Xray-core v26.10.10) is `{version, address, port}`.
 * Limited by protocol because other outbounds (`dns`) have a `settings.address` that is not a server.
 */
private val FLAT_ADDRESS_PROTOCOLS = setOf("hysteria")

private fun JsonObject.hasFlatAddress(): Boolean =
    ((this["protocol"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()) in FLAT_ADDRESS_PROTOCOLS

private fun norm(s: String) = s.lowercase().removeSuffix(".")
