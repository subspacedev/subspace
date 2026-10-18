// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.getsub.core.model.KcpHeader
import space.getsub.core.model.KcpHeaderResult
import space.getsub.core.model.KcpMask
import space.getsub.core.model.KcpObfuscation
import space.getsub.core.model.KcpRules
import space.getsub.core.model.isKcpNetwork

/**
 * Rewrites a raw mKCP outbound's pre-v26.10 obfuscation into the masks the pinned core reads.
 *
 * Xray-core v26.10.10 still *parses* `kcpSettings.header` and `kcpSettings.seed`
 * (`infra/conf/transport_method.go:540-541`) and then never reads them, so such a config builds and
 * sends bare KCP to a server that expects obfuscation: a silent timeout (M9-D2 research §3.1, the trap
 * ranked first in §5.5). The obfuscation now lives in `finalmask.udp` `mkcp-legacy` masks (§3.2).
 *
 * The translation is the projection's (`:core:parser` `XrayJsonTransport.kcpOptions`, `classicObfuscation`)
 * run through [KcpRules.masks], so the raw row and its typed projection describe the same wire: seed (only
 * `""` means none, ruling D2-R6) and header (`header.type`, or a bare string; `wechat-video` is `wechat`)
 * become `[seed or original, header]`; header-only gets `original` first (ruling D2-R3). The two inert keys
 * are then removed. A `none` header with no seed is bare KCP, so only the keys go.
 *
 * Touched only when all hold: `streamSettings.network` is `kcp`/`mkcp` (any case); `kcpSettings` is an
 * object carrying `header` or `seed`; `finalmask` is absent or an object whose `udp` is absent, null, an
 * empty array, or an array of `noise` masks only. A config that already has an `mkcp-legacy` (or any other
 * non-noise) mask is the v26.10 shape and wins. Noise is client-only junk the server never sees (ruling
 * D2-R12), so noise-only masks still lack the obfuscation: the legacy-derived masks go in front of them,
 * `[seed or original, header, noise...]`, the order the typed path writes (final review M3). A header with no mask
 * (`http`, an unknown name), or a `dns` header carrying a non-empty `domain` (ruling D2-R11), is left exactly
 * as written: the projection marks that row not connectable.
 * Idempotent (the rewritten outbound no longer has the keys). Pure; never throws.
 */
internal object LegacyKcp {
    fun apply(outbounds: JsonArray): JsonArray {
        var changed = false
        val rewritten =
            outbounds.map { element ->
                val result = (element as? JsonObject)?.let(::rewrite)
                if (result != null) changed = true
                result ?: element
            }
        return if (changed) JsonArray(rewritten) else outbounds
    }

    @Suppress("ReturnCount")
    private fun rewrite(outbound: JsonObject): JsonObject? {
        val stream = outbound["streamSettings"] as? JsonObject ?: return null
        val network = (stream["network"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (network == null || !isKcpNetwork(network)) return null
        val kcp = stream["kcpSettings"] as? JsonObject ?: return null
        if (!kcp.containsKey("header") && !kcp.containsKey("seed")) return null
        val (finalmask, noise) = maskFreeFinalmask(stream) ?: return null
        val masks = masksFor(kcp) ?: return null

        val newStream = stream.toMutableMap()
        newStream["kcpSettings"] = JsonObject(kcp - "header" - "seed")
        if (masks.isNotEmpty()) {
            val udp = JsonArray(masks.map { Json.parseToJsonElement(FinalmaskJson.kcpMask(it)) } + noise)
            newStream["finalmask"] = JsonObject(finalmask + ("udp" to udp))
        }
        return JsonObject(outbound + ("streamSettings" to JsonObject(newStream)))
    }

    /**
     * The stream's `finalmask` (empty when absent) and its `udp` noise masks, if it has no other `udp` mask yet,
     * else null: leave as written.
     */
    @Suppress("ReturnCount")
    private fun maskFreeFinalmask(stream: JsonObject): Pair<JsonObject, List<JsonElement>>? {
        val finalmask =
            when (val f = stream["finalmask"]) {
                null, is JsonNull -> return JsonObject(emptyMap()) to emptyList()
                is JsonObject -> f
                else -> return null
            }
        return when (val udp = finalmask["udp"]) {
            null, is JsonNull -> finalmask to emptyList()
            is JsonArray -> (finalmask to udp.toList()).takeIf { udp.all(::isNoiseMask) }
            else -> null
        }
    }

    private fun isNoiseMask(element: JsonElement): Boolean =
        (element as? JsonObject)?.string("type")?.lowercase() == "noise"

    /**
     * The masks the classic fields mean, or null when they cannot be written: a header with no mask, or (ruling
     * D2-R11) a `dns` header carrying a non-empty `domain`. The legacy DNS header had its own domain (Xray's
     * DNSAuthenticator, default www.baidu.com, commit 336b2da), and `{header:"dns"}` alone would put the default
     * on the wire; the projection marks that row DnsHeaderDomain (not connectable).
     */
    private fun masksFor(kcp: JsonObject): List<KcpMask>? {
        val seed = kcp.string("seed")?.takeIf { it.isNotEmpty() }
        val headerObject = kcp["header"] as? JsonObject
        val headerName = headerObject?.string("type") ?: kcp.string("header")
        val parsed = KcpRules.headerFromLinkName(headerName)
        val header = (parsed as? KcpHeaderResult.Ok)?.header
        val domainLost = header == KcpHeader.DNS && !headerObject?.string("domain").isNullOrEmpty()
        return if (parsed == KcpHeaderResult.Unsupported || domainLost) {
            null
        } else {
            KcpRules.masks(KcpObfuscation(seed, header, legacyOriginal = seed == null && header != null))
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
