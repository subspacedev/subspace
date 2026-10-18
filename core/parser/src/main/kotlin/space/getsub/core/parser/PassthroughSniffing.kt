// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One row's resolved sniffing, as the tunnel applies it (M9-E spec §2).
 *
 * [replacesOwn] is true only for your own override: a provider's value or the default never
 * replaces a raw config's own block.
 */
public data class SniffingDecision(
    val enabled: Boolean,
    val replacesOwn: Boolean,
)

/**
 * The SOCKS inbound's `sniffing` as the app emits it. Decided by [emittedSniffing], which both
 * `RawConfigComposer` (to write it) and [analysePassthrough] (to judge the config's rules against
 * it) read, so the advisory and the config cannot disagree.
 */
public sealed interface EmittedSniffing {
    /** The raw config's own block, carried verbatim. */
    public data class Own(
        val block: JsonObject,
    ) : EmittedSniffing

    /** The app's block: `enabled: true` with these `destOverride` values. */
    public data class Generated(
        val destOverride: List<String>,
    ) : EmittedSniffing

    /** No `sniffing` block. */
    public data object None : EmittedSniffing
}

/** What the typed path has always sniffed. `fakedns` is added when a DNS plan asks for it. */
public val DEFAULT_SNIFFING_OVERRIDES: List<String> = listOf("http", "tls", "quic")

private const val FAKEDNS = "fakedns"

/**
 * The app's `destOverride` for a resolved sniffing value, or null for no block (M9-E spec §2).
 *
 * Off still keeps `fakedns` alone while the DNS plan uses FakeDNS: that override is what maps a
 * fake address (198.18.0.0/15) back to its domain, and without it nearly every connection fails.
 * Only `http`/`tls`/`quic` are dropped. `"fakedns"` is a destOverride value Xray-core v26.10.10
 * accepts on its own (`infra/conf/xray.go:69-84`).
 */
public fun generatedSniffingOverrides(
    enabled: Boolean,
    fakeDns: Boolean,
): List<String>? =
    when {
        enabled && fakeDns -> DEFAULT_SNIFFING_OVERRIDES + FAKEDNS
        enabled -> DEFAULT_SNIFFING_OVERRIDES
        fakeDns -> listOf(FAKEDNS)
        else -> null
    }

/**
 * Which `sniffing` the SOCKS inbound gets. [ownBlock] is the config's own block where the branch
 * may keep it ([ownSocksSniffing] on the pure passthrough branch, null otherwise); it is kept
 * unless [decision] is your own override. Otherwise the resolved value applies.
 */
public fun emittedSniffing(
    ownBlock: JsonObject?,
    decision: SniffingDecision,
    fakeDns: Boolean,
): EmittedSniffing =
    if (ownBlock != null && !decision.replacesOwn) {
        EmittedSniffing.Own(ownBlock)
    } else {
        generatedSniffingOverrides(decision.enabled, fakeDns)?.let { EmittedSniffing.Generated(it) }
            ?: EmittedSniffing.None
    }

/**
 * The config's own sniffing block on its first SOCKS inbound, the one block the composer can keep.
 *
 * Not replaced by our default unconditionally: the target panel sniffs `["http","tls"]` with
 * `domainStrategy: "AsIs"`, so adding `quic` would start matching its RU domain rule against
 * HTTP/3 traffic that is proxied today (research §5b.5).
 */
public fun ownSocksSniffing(root: JsonObject): JsonObject? =
    (root["inbounds"] as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.firstOrNull { (it["protocol"] as? JsonPrimitive)?.content == "socks" }
        ?.get("sniffing") as? JsonObject

/** The `destOverride` values [this] block actually sniffs: none unless `enabled` is true. */
internal fun EmittedSniffing.sniffedOverrides(): List<String> =
    when (this) {
        is EmittedSniffing.Own -> block.sniffedOverrides()
        is EmittedSniffing.Generated -> destOverride
        EmittedSniffing.None -> emptyList()
    }

/** `destOverride` strings of a `sniffing` object whose `enabled` is true; empty otherwise. */
internal fun JsonObject.sniffedOverrides(): List<String> =
    if (this["enabled"].primitiveContent() != "true") {
        emptyList()
    } else {
        (this["destOverride"] as? JsonArray).orEmpty().mapNotNull { it.primitiveContent() }
    }

private fun JsonElement?.primitiveContent(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
