// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("Filename", "MatchingDeclarationName", "ReturnCount", "ktlint:standard:filename")

package space.getsub.core.parser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import space.getsub.core.model.KcpHeader
import space.getsub.core.model.KcpHeaderResult
import space.getsub.core.model.KcpObfuscation
import space.getsub.core.model.KcpRules
import space.getsub.core.model.KcpUnsupported

/**
 * What an Xray `finalmask` object says about mKCP obfuscation.
 *
 * @property obfuscation null when the finalmask has no `udp` masks at all, so a lower-priority source
 *   (a link's `seed`/`headerType`) may speak; otherwise the masks as typed fields, with
 *   [KcpObfuscation.unsupported] set when they cannot be reproduced by [KcpRules.masks].
 */
internal data class KcpFinalmaskResult(
    val obfuscation: KcpObfuscation?,
)

/** One `udp[]` entry, typed. */
private sealed interface Parsed {
    data object Original : Parsed

    data class Seed(
        val value: String,
    ) : Parsed

    data class Header(
        val header: KcpHeader,
    ) : Parsed

    data class Bad(
        val reason: KcpUnsupported,
    ) : Parsed
}

private fun failed(reason: KcpUnsupported) = KcpFinalmaskResult(KcpObfuscation(null, null, false, reason))

/**
 * Reads the `udp` list of a `fm=` query value or raw `streamSettings.finalmask` for an mKCP stream.
 * Never throws and never puts a seed in anything it returns that prints.
 *
 * Understood: `mkcp-legacy` masks whose `settings` are `{}`/empty (original), `{value}` (seed) or `{header}`,
 * in the orders [KcpRules.masks] writes: `[original]`, `[seed]`, `[original|seed, header]`. Anything else
 * (a header with no leading original or seed, `noise`, another mask type, a `dns` header carrying a domain,
 * a header with a seed value, a non-object entry) is not reproducible and names its reason.
 *
 * [trailingNoiseAllowed] (ruling D2-R12, raw JSON only): `noise` masks after every `mkcp-legacy` mask are
 * client-only junk the server never sees (research "Verified for D2" (c): `[S,H,N]` client ↔ `[S,H]` server),
 * so they are dropped before reading and the row stays connectable; the raw config keeps or replaces them as
 * the injector does. Noise alone is then "no masks" (null): `:core:xray`'s `LegacyKcp` puts the masks the
 * legacy `kcpSettings` fields mean in front of the noise, so the caller reads those fields, as for no udp at
 * all (final review M3). A link's `fm=` keeps M9-D1's rule (any noise is UnknownMask), so link callers leave
 * this false.
 */
internal fun readKcpFinalmask(
    finalmask: JsonObject,
    trailingNoiseAllowed: Boolean = false,
): KcpFinalmaskResult {
    val all = (finalmask["udp"] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return KcpFinalmaskResult(null)
    val list = if (trailingNoiseAllowed) all.dropLastWhile { it.isNoiseMask() } else all
    if (list.isEmpty()) return KcpFinalmaskResult(null)
    val parsed =
        list.map { entry ->
            (entry as? JsonObject)?.let(::parseMask) ?: Parsed.Bad(KcpUnsupported.UnknownMask)
        }
    parsed.filterIsInstance<Parsed.Bad>().firstOrNull()?.let { return failed(it.reason) }
    return when {
        parsed.size == 1 && parsed[0] is Parsed.Original -> KcpFinalmaskResult(KcpObfuscation(null, null, true))
        parsed.size == 1 && parsed[0] is Parsed.Seed ->
            KcpFinalmaskResult(KcpObfuscation((parsed[0] as Parsed.Seed).value, null, false))
        parsed.size == 2 && parsed[1] is Parsed.Header -> secondIsHeader(parsed[0], (parsed[1] as Parsed.Header).header)
        else -> failed(KcpUnsupported.UnknownMask)
    }
}

private fun secondIsHeader(
    first: Parsed,
    header: KcpHeader,
): KcpFinalmaskResult =
    when (first) {
        Parsed.Original -> KcpFinalmaskResult(KcpObfuscation(null, header, true))
        is Parsed.Seed -> KcpFinalmaskResult(KcpObfuscation(first.value, header, false))
        else -> failed(KcpUnsupported.UnknownMask)
    }

private fun parseMask(entry: JsonObject): Parsed {
    if (entry.stringValue("type")?.lowercase() != "mkcp-legacy") return Parsed.Bad(KcpUnsupported.UnknownMask)
    val rawSettings = entry["settings"]
    if (rawSettings != null && rawSettings !is JsonObject) return Parsed.Bad(KcpUnsupported.UnknownMask)
    val settings = rawSettings
    val headerName = settings?.stringValue("header").orEmpty()
    val value = settings?.stringValue("value").orEmpty()
    return when {
        headerName.isEmpty() && value.isEmpty() -> Parsed.Original
        headerName.isEmpty() -> Parsed.Seed(value)
        else -> parseHeader(headerName, value)
    }
}

private fun parseHeader(
    name: String,
    value: String,
): Parsed =
    when (val h = KcpRules.headerFromLinkName(name)) {
        // `{"header":"none"}` with no value is the same as `{}`: a second original, which KcpRules.masks cannot
        // write (it never emits two), so `[original, none]` is UnknownMask rather than quietly one original.
        // "none" with a value is a seed written oddly; stay conservative.
        KcpHeaderResult.None -> if (value.isEmpty()) Parsed.Original else Parsed.Bad(KcpUnsupported.UnknownMask)
        KcpHeaderResult.Unsupported -> Parsed.Bad(KcpUnsupported.UnknownHeader)
        is KcpHeaderResult.Ok ->
            when {
                value.isEmpty() -> Parsed.Header(h.header)
                h.header == KcpHeader.DNS -> Parsed.Bad(KcpUnsupported.DnsHeaderDomain)
                else -> Parsed.Bad(KcpUnsupported.UnknownMask)
            }
    }

private fun kotlinx.serialization.json.JsonElement.isNoiseMask(): Boolean =
    (this as? JsonObject)?.stringValue("type")?.lowercase() == "noise"
