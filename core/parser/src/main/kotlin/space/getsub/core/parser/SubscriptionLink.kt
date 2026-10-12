// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import java.net.URLDecoder

/**
 * A pasted subscription link split per Happ's documented scheme,
 * `[link]#title?[fragment]&[resolve-address]&[host]&[insecure]` (M9-B spec §2.2, research note).
 * Never throws (ARCHITECTURE.md §7). Every field but [insecure] is a secret or user content, so
 * [toString] prints none of them (ARCHITECTURE.md §5.6).
 */
public data class SubscriptionLink(
    val plainUrl: String,
    val title: String?,
    val resolveAddress: String?,
    val hostHeader: String?,
    val fragment: String?,
    val insecure: Boolean,
) {
    override fun toString(): String =
        "SubscriptionLink(plainUrl=<redacted>, title=${if (title == null) "none" else "present"}, " +
            "resolveAddress=${resolveAddress.presence()}, hostHeader=${hostHeader.presence()}, " +
            "fragment=${fragment.presence()}, insecure=$insecure)"

    public companion object {
        public fun parse(raw: String): SubscriptionLink {
            val trimmed = raw.trim()
            val hash = trimmed.indexOf('#')
            if (hash < 0) return SubscriptionLink(trimmed, null, null, null, null, insecure = false)

            val rest = trimmed.substring(hash + 1)
            val query = rest.indexOf('?')
            val titlePart = if (query < 0) rest else rest.substring(0, query)
            val params = if (query < 0) emptyMap() else parseParams(rest.substring(query + 1))
            return SubscriptionLink(
                plainUrl = trimmed.substring(0, hash),
                title = decode(titlePart).trim().ifEmpty { null },
                resolveAddress = params["resolve-address"]?.ifEmpty { null },
                hostHeader = params["host"]?.ifEmpty { null },
                fragment = params["fragment"]?.ifEmpty { null },
                insecure = "insecure" in params,
            )
        }

        private fun parseParams(text: String): Map<String, String> {
            val result = linkedMapOf<String, String>()
            text.split('&').forEach { pair ->
                if (pair.isBlank()) return@forEach
                val eq = pair.indexOf('=')
                val key = (if (eq < 0) pair else pair.substring(0, eq)).trim().lowercase()
                val value = if (eq < 0) "" else decode(pair.substring(eq + 1)).trim()
                if (key.isNotEmpty()) result.putIfAbsent(key, value)
            }
            return result
        }

        // URLDecoder turns '+' into a space; a literal plus in a title must survive, so escape it first.
        private fun decode(text: String): String =
            runCatching { URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8.name()) }.getOrDefault(text)
    }
}

private fun String?.presence(): String = if (this == null) "none" else "<redacted>"
