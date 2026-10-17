// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.Obfuscation
import space.getsub.core.model.Profile

/**
 * One server to measure: [key] is the caller's own id (a profile id), [xrayJson]
 * the full typed config. `toString` redacts the config (ARCHITECTURE.md §5.6).
 *
 * [certProbe] is `:bg`'s decision that, should this server fail, `:ping` may run the
 * certificate probe on it ([CertificateProbe]); `pingBatch` itself never reads it.
 */
public data class PingTarget(
    val key: Long,
    val xrayJson: String,
    val certProbe: Boolean = false,
) {
    override fun toString(): String = "PingTarget(key=$key, xrayJson=<redacted>, certProbe=$certProbe)"
}

/**
 * Seam over the native `pingBatch` call so the model is JVM-testable.
 * [requestPayload] is the `payload` object text from [PingBatch.request]; the
 * result is the response `data` object's text, or null when absent. Throws
 * [XrayException] on a whole-call failure (its message may quote config values).
 */
public fun interface PingBatchCall {
    public fun invoke(requestPayload: String): String?
}

/**
 * Production [PingBatchCall]. Must run in the `:ping` process: libXray refuses
 * `pingBatch` in a process holding a managed instance.
 *
 * [LibXrayInvoke.callJson], not `call(…, JSONObject(payload))`: the payload is
 * never re-parsed through `org.json`, so a `JSONException` quoting config text
 * cannot arise here (ARCHITECTURE.md §5.6).
 */
public class LibXrayPingBatchCall : PingBatchCall {
    override fun invoke(requestPayload: String): String? =
        LibXrayInvoke.callJson("pingBatch", requestPayload)?.toString()
}

/**
 * Request/response model for libXray's `pingBatch` (v26.10.10; `docs/agent/research/libxray-api.md`).
 *
 * A failed item arrives as `success:false` with a sentinel `delay` (`10000` error,
 * `11000` timeout). The sentinel is never read: a failure is
 * [LatencyOutcome.UNREACHABLE] whatever `delay` says (§10.1).
 */
public object PingBatch {
    /** libXray's `maxPingBatchConfigs`; larger requests are rejected by the core. */
    public const val MAX_CONFIGS: Int = 5

    private const val OUTBOUND_TAG = "proxy"

    /** Builds the `pingBatch` payload. [timeoutSeconds] is in seconds. */
    public fun request(
        targets: List<PingTarget>,
        timeoutSeconds: Int,
        url: String,
    ): String {
        require(targets.size <= MAX_CONFIGS) { "pingBatch takes at most $MAX_CONFIGS configs, got ${targets.size}" }
        return buildJsonObject {
            put(
                "configs",
                buildJsonArray {
                    targets.forEach {
                        add(
                            buildJsonObject {
                                put("xrayJson", it.xrayJson)
                                put("outboundTag", OUTBOUND_TAG)
                            },
                        )
                    }
                },
            )
            put("timeout", timeoutSeconds)
            put("url", url)
        }.toString()
    }

    /**
     * Maps the response [data] (text of the `data` object, or null) to one result
     * per target. Missing, malformed or short `results` fail the affected targets.
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    public fun results(
        targets: List<PingTarget>,
        data: String?,
    ): Map<Long, LatencyResult> {
        val entries =
            try {
                data?.let { Json.parseToJsonElement(it).jsonObject["results"]?.jsonArray }
            } catch (e: Exception) {
                // Deliberate: a parse error message can quote the payload (§5.6).
                null
            }.orEmpty()
        return targets.withIndex().associate { (i, t) -> t.key to toResult(entries.getOrNull(i)) }
    }

    /**
     * Runs [call] and maps the outcome. A whole-call [XrayException] fails every
     * target; its message is dropped because it can quote config values (§5.6).
     */
    @Suppress("SwallowedException")
    public fun measure(
        call: PingBatchCall,
        targets: List<PingTarget>,
        timeoutSeconds: Int,
        url: String,
    ): Map<Long, LatencyResult> =
        try {
            results(targets, call.invoke(request(targets, timeoutSeconds, url)))
        } catch (e: XrayException) {
            results(targets, null)
        }

    private fun toResult(entry: JsonElement?): LatencyResult {
        val item = entry as? JsonObject
        val success = (item?.get("success") as? JsonPrimitive)?.booleanOrNull == true
        val delay = (item?.get("delay") as? JsonPrimitive)?.longOrNull
        return if (success && delay != null && delay in 0..Int.MAX_VALUE) {
            LatencyResult.ok(delay.toInt())
        } else {
            LatencyResult.failed(LatencyOutcome.UNREACHABLE)
        }
    }
}

/** pingBatch reads only `outbounds`, so the socks inbound's port is irrelevant; any valid value will do. */
private const val PING_CONFIG_SOCKS_PORT = 10808

/** Resolves the server's own hostname inside the throwaway instance; never governs user traffic. */
private const val PING_CONFIG_DNS = "1.1.1.1"

/**
 * The typed config for measuring one server, or null when the protocol is
 * unsupported. [obfuscation] keeps a server reachable only fragmented from
 * pinging as dead (M9-A spec §3.4).
 */
public fun pingConfig(
    profile: Profile,
    obfuscation: Obfuscation?,
): String? =
    when (
        val config =
            XrayConfigGenerator.generate(
                profile,
                TunnelSettings(
                    socksPort = PING_CONFIG_SOCKS_PORT,
                    dnsServer = PING_CONFIG_DNS,
                    enableSniffing = false,
                    obfuscation = obfuscation,
                ),
            )
    ) {
        is ConfigResult.Ok -> config.json
        is ConfigResult.Unsupported -> null
    }
