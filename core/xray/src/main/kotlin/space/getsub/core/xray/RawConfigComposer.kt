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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import space.getsub.core.parser.EmittedSniffing
import space.getsub.core.parser.SniffingDecision
import space.getsub.core.parser.emittedSniffing
import space.getsub.core.parser.ownSocksSniffing

/** Why a stored config could not be composed into something runnable. */
public enum class ComposeFailure {
    NotJson,
    NoOutbounds,

    /**
     * No outbound or balancer in the stored config can be named by the app's
     * generated rules — see `OverrideTarget.Unresolvable`.
     *
     * Renamed from `MissingOverrideProxy` in M7.5: the absence of a literal
     * `proxy` tag stopped being the question once the target became resolvable.
     */
    UnresolvableOverrideTarget,

    /** A reserved override tag exists, but its outbound protocol would give that tag different semantics. */
    IncompatibleOverrideOutbound,

    /**
     * [OverrideBlocks]'s `routingJson`/`dnsJson`/`extraOutboundsJson` did not
     * parse. Task 4's callers are expected to hand `compose` well-formed text
     * of its own making, but `compose` must not throw regardless of who built
     * [OverrideBlocks] — distinct from [NotJson] because that failure describes
     * the stored config itself, not the app's own override text.
     */
    InvalidOverride,
}

/** The outcome of composing a stored config. */
public sealed interface ComposeResult {
    public data class Ok(val json: String) : ComposeResult {
        // ARCHITECTURE.md §5.6: this is the whole composed config — server UUID, REALITY
        // private key, the user's routing/dns text, all of it.
        override fun toString(): String = "Ok(<redacted, ${json.length} bytes>)"
    }

    public data class Failed(val reason: ComposeFailure) : ComposeResult
}

/**
 * The app's `routing` and `dns` blocks, when they replace the config's own.
 *
 * Carried as already-emitted JSON text rather than as a typed model, so
 * [XrayConfigGenerator] stays the single author of those bytes and the two
 * paths cannot drift in what a rule looks like.
 *
 * ARCHITECTURE.md §5.6: routing entries are domains the user visits.
 */
public data class OverrideBlocks(
    val routingJson: String,
    val dnsJson: String,
    val extraOutboundsJson: List<String>,
) {
    override fun toString(): String = "OverrideBlocks(<redacted>)"
}

/**
 * Rewrites a stored raw Xray config into one this app can actually run.
 *
 * ARCHITECTURE.md §6's passthrough half. Unlike [XrayConfigGenerator], which
 * writes a config from a typed model, this **edits a parsed tree** — anything
 * the design does not name survives untouched, which is the entire point of
 * storing the bytes (ARCHITECTURE.md §6: extraction is lossy and the loss is permanent).
 *
 * Five rewrites are unconditional, each forced by an existing invariant rather
 * than by preference:
 *
 * - `inbounds` — the config's listeners are on ports we did not allocate
 *   (§10.6; the target panel ships 10808/10809) and tun2socks dials ours.
 * - `env` — Go cannot see a Java `setenv`, so the asset directory travels in the
 *   invoke `env` object (ARCHITECTURE.md §6).
 * - `log` — `access: "none"`, or gomobile pipes one line per destination the
 *   user reaches into logcat (ARCHITECTURE.md §5.6, found on device during M1).
 * - `stats`/`policy`/`metrics` — a config's own metrics listener is an
 *   unaudited open port, and counters are M8's.
 * - `api` — believed from upstream documentation, not verified against the pinned
 *   Xray-core, that `api.listen` starts an unauthenticated gRPC control plane on
 *   its own address without needing an inbound, so stripping `inbounds` would not
 *   neutralise it; stripping is safe either way. Nothing in the app reads a config's `api` (its own metrics
 *   path is `metrics`). Like `stats`, this is an exception to the pure branch's
 *   "as written" promise. A top-level `reverse` is not stripped here: it is
 *   rejected earlier, by `analysePassthrough`.
 *
 * Everything else depends on [override]: null runs the config's `routing` and
 * `dns` as written; non-null deletes both and substitutes the app's.
 */
public object RawConfigComposer {
    // ignoreUnknownKeys governs typed decodeFromString; every use below is parseToJsonElement,
    // which it never affects, so it is left out rather than left looking load-bearing.
    private val LENIENT = Json { isLenient = true }

    /**
     * @param assetDir what `xray.location.asset` must name — the active rule
     *   set's live generation when routing is on, the curated flat root
     *   otherwise. Resolved by the caller, which already computes it.
     */
    @Suppress("ReturnCount")
    public fun compose(
        rawJson: String,
        settings: TunnelSettings,
        assetDir: String,
        override: OverrideBlocks?,
    ): ComposeResult {
        val root =
            try {
                LENIENT.parseToJsonElement(rawJson) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return ComposeResult.Failed(ComposeFailure.NotJson)

        val outbounds = root["outbounds"] as? JsonArray
        if (outbounds.isNullOrEmpty()) return ComposeResult.Failed(ComposeFailure.NoOutbounds)

        // M9-A spec §3.3: both branches. Applied to the config's own outbounds only; the
        // override's direct/block/dns-out extras are never eligible. The legacy udpHop and kcp rewrites
        // run first so injected noise lands before the `udphop` mask one produces, never after it, and
        // after the `mkcp-legacy` masks the other produces: noise must be the entry nearest the wire
        // there (M9-D2 research §3.3). Mux (M9-E spec §3) goes on the same outermost outbounds, both branches.
        val ownOutbounds =
            AddressPinInjector.apply(
                MuxInjector.apply(
                    ObfuscationInjector.apply(LegacyKcp.apply(LegacyUdpHop.apply(outbounds)), settings.obfuscation),
                    settings.mux,
                ),
                settings.addressPin,
            )

        val kept =
            root.filterKeys { it !in STRIPPED }
                .toMutableMap()
        kept["outbounds"] = ownOutbounds

        kept["log"] = buildJsonObject {
            put("access", "none")
            put("loglevel", "warning")
        }
        kept["env"] = buildJsonObject { put("xray.location.asset", assetDir) }

        if (override != null) {
            val parsedOverride =
                try {
                    ParsedOverride(
                        routing = LENIENT.parseToJsonElement(override.routingJson),
                        dns = LENIENT.parseToJsonElement(override.dnsJson),
                        extraOutbounds = override.extraOutboundsJson.map(LENIENT::parseToJsonElement),
                    )
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return ComposeResult.Failed(ComposeFailure.InvalidOverride)

            kept["routing"] = withDeclaredBalancers(parsedOverride.routing, root["routing"])
            kept["dns"] = parsedOverride.dns
            kept["outbounds"] = JsonArray(ownOutbounds + parsedOverride.extraOutbounds)

            // M8.5 spec §2.4: only the override branch may carry these — it already
            // replaces `routing`/`dns` wholesale, so three more blocks change
            // nothing about the pure branch's "as written" promise, which this
            // `if (override != null)` guard is what keeps intact.
            kept.addMetricsBlocks(settings.metricsPort)
        }

        // M9-E spec §2. The override branch already owns `dns` wholesale (just above), so it emits
        // the resolved value, fakedns included when its plan asks. The pure branch keeps the
        // config's own block as written (M7 spec §4.2, research §5b.5) unless the value is the
        // user's own override; a config with no block of its own takes the resolved value.
        // The decision is [emittedSniffing], shared with the editor's passthrough advisory.
        val sniffing =
            emittedSniffing(
                ownBlock = if (override == null) ownSocksSniffing(root) else null,
                decision = SniffingDecision(settings.enableSniffing, settings.sniffingReplacesOwn),
                fakeDns = settings.dns?.fakeDns == true,
            )
        val inbounds = inboundsJson(settings, sniffing, inboundTagsOf(root))
        return ComposeResult.Ok(render(kept, inbounds))
    }

    /**
     * The override's `routing` object, carrying the stored config's own
     * `balancers` declaration forward.
     *
     * The override replaces `routing` wholesale, which takes everything inside
     * it — including the `balancers` array. That was harmless while the app's
     * rules only ever named an outbound; since M7.5 they may name the config's
     * own balancer, and the core refuses a rule naming a balancer the config
     * does not declare (`RawConfigComposerXrayTest` pins it). Without this,
     * every balancer config is refused at config build for a name only this
     * carries.
     *
     * **`balancers` only.** The app owns the rules and the domain strategy on
     * this branch by design (spec §4.2). A balancer declaration is not a routing
     * decision: it is the definition of a name our own rules refer to, the same
     * way the config's own `outbounds` are kept for our `outboundTag` to name.
     * Its `strategy` and any `observatory`/`burstObservatory` block it leans on
     * are the config's own and already survive at the top level.
     */
    private fun withDeclaredBalancers(
        overrideRouting: JsonElement,
        ownRouting: JsonElement?,
    ): JsonElement {
        val balancers = (ownRouting as? JsonObject)?.get("balancers") as? JsonArray
        val routing = overrideRouting as? JsonObject
        return if (balancers.isNullOrEmpty() || routing == null) {
            overrideRouting
        } else {
            JsonObject(routing + ("balancers" to balancers))
        }
    }

    /**
     * Merges `stats`/`policy`/`metrics` into an already-mutable kept-keys map,
     * when [port] is non-null.
     *
     * Split out of [compose] so its own branching (a null check and a `forEach`)
     * does not count against that function's cyclomatic complexity — the
     * decision itself stays exactly where M8.5 spec §2.4 requires it: only
     * [compose]'s override branch calls this at all, so a null
     * [TunnelSettings.metricsPort] (breakdown off, or no port could be
     * allocated) leaves the map exactly as [STRIPPED] left it, on both
     * branches.
     */
    private fun MutableMap<String, JsonElement>.addMetricsBlocks(port: Int?) {
        val p = port ?: return
        metricsBlocks(p).forEach { (key, value) -> this[key] = value }
    }

    /** Keys removed outright before anything is added back. */
    private val STRIPPED = setOf("inbounds", "log", "env", "stats", "policy", "metrics", "api")

    /** [OverrideBlocks], once its three JSON strings have parsed successfully. */
    private data class ParsedOverride(
        val routing: JsonElement,
        val dns: JsonElement,
        val extraOutbounds: List<JsonElement>,
    )

    private fun inboundsJson(
        settings: TunnelSettings,
        sniffing: EmittedSniffing,
        tags: InboundTags,
    ): String {
        val socks =
            when (sniffing) {
                is EmittedSniffing.Own ->
                    socksInboundJsonPreservingSniffing(
                        settings.socksPort,
                        sniffing.block,
                        settings.proxyCredentials,
                        tags.socks,
                    )
                is EmittedSniffing.Generated ->
                    socksInboundJson(
                        settings.socksPort,
                        SniffingSettings(sniffing.destOverride),
                        settings.proxyCredentials,
                        tags.socks,
                    )
                EmittedSniffing.None ->
                    socksInboundJson(settings.socksPort, null, settings.proxyCredentials, tags.socks)
            }
        val http = settings.httpPort?.let { httpInboundJson(it, settings.proxyCredentials, tags.http) }
        return listOfNotNull(socks, http).joinToString(",\n")
    }

    /** The client-facing tags whose references survive in the config's own routing block. */
    private data class InboundTags(val socks: String, val http: String)

    /**
     * Preserves the original client inbound tags while replacing their listeners and ports.
     *
     * The pure branch keeps the config's routing tree untouched. If a rule names the original
     * SOCKS/HTTP tag through `inboundTag`, changing that tag on the injected inbound makes the rule
     * silently stop matching even though xray-core accepts the config. Defaults are used only when
     * the source has no usable tag for that protocol. The HTTP fallback is kept distinct from the
     * SOCKS tag so the injected pair can never create a duplicate-tag config by itself.
     */
    private fun inboundTagsOf(root: JsonObject): InboundTags {
        val socks = root.inboundTagFor("socks") ?: DEFAULT_SOCKS_INBOUND_TAG
        val requestedHttp = root.inboundTagFor("http") ?: DEFAULT_HTTP_INBOUND_TAG
        val http =
            when {
                requestedHttp != socks -> requestedHttp
                DEFAULT_HTTP_INBOUND_TAG != socks -> DEFAULT_HTTP_INBOUND_TAG
                else -> FALLBACK_HTTP_INBOUND_TAG
            }
        return InboundTags(socks = socks, http = http)
    }

    private fun JsonObject.inboundTagFor(protocol: String): String? =
        (this["inbounds"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.firstOrNull { inbound -> inbound.stringValue("protocol") == protocol }
            ?.stringValue("tag")
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.stringValue(key: String): String? =
        when (val value = this[key]) {
            null, is JsonNull -> null
            is JsonPrimitive -> value.content
            else -> null
        }

    private const val DEFAULT_SOCKS_INBOUND_TAG = "socks-in"
    private const val DEFAULT_HTTP_INBOUND_TAG = "http-in"
    private const val FALLBACK_HTTP_INBOUND_TAG = "subspace-http-in"

    /**
     * Emits the tree with `inbounds` spliced in as text.
     *
     * Keys are written in sorted order so the same input always produces the
     * same bytes — the determinism contract ARCHITECTURE.md §6 places on [XrayConfigGenerator],
     * met here by ordering rather than by hand-writing.
     */
    private fun render(
        kept: Map<String, JsonElement>,
        inbounds: String,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("""  "inbounds": [""")
        sb.appendLine(inbounds)
        sb.appendLine("""  ],""")
        val entries = kept.entries.sortedBy { it.key }
        entries.forEachIndexed { index, (key, value) ->
            val comma = if (index == entries.size - 1) "" else ","
            sb.appendLine("""  ${jsonString(key)}: $value$comma""")
        }
        sb.append("}")
        return sb.toString()
    }
}
