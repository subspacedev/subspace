// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MatchingDeclarationName") // File groups the whole inbound pair, not just SniffingSettings.

package space.getsub.core.xray

import kotlinx.serialization.json.JsonObject
import space.getsub.core.model.ProxyCredentials

/**
 * A SOCKS inbound's `sniffing` block.
 *
 * Traffic reaches the inbound from tun2socks addressed to an **IP**; without
 * sniffing no domain is ever recovered, and every `domain`/`geosite:` rule —
 * ours or a passthrough config's own — matches nothing. The config still runs
 * and the tunnel still carries traffic, which is why this is §10's category
 * rather than an obvious bug.
 */
internal data class SniffingSettings(val destOverride: List<String>)

/** What the typed path has always emitted. `fakedns` is appended when a DNS plan asks for it. */
internal val DEFAULT_SNIFFING = SniffingSettings(listOf("http", "tls", "quic"))

/**
 * The `sniffing` block for a resolved sniffing value (M9-E spec §2), or null for no block.
 *
 * Off still keeps `fakedns` alone while the DNS plan uses FakeDNS: that override is what maps a
 * fake address (198.18.0.0/15) back to its domain, and without it nearly every connection fails.
 * Only `http`/`tls`/`quic` are dropped. `"fakedns"` is a destOverride value Xray-core v26.10.10
 * accepts on its own (`infra/conf/xray.go:69-84`).
 */
internal fun sniffingFor(
    enabled: Boolean,
    fakeDns: Boolean,
): SniffingSettings? =
    when {
        enabled && fakeDns -> SniffingSettings(DEFAULT_SNIFFING.destOverride + FAKEDNS)
        enabled -> DEFAULT_SNIFFING
        fakeDns -> SniffingSettings(listOf(FAKEDNS))
        else -> null
    }

private const val FAKEDNS = "fakedns"

/**
 * The loopback SOCKS inbound, emitted identically by both config paths.
 *
 * §6: loopback only. Never `0.0.0.0` — that turns the phone into an open proxy
 * for anyone on the same Wi-Fi.
 *
 * [sniffing] is null when the caller wants no `sniffing` block at all. The
 * passthrough path passes the *config's own* block rather than
 * [DEFAULT_SNIFFING], because substituting ours changes which of the config's
 * rules match (M7 spec §4.2).
 * [tag] defaults to the typed path's stable name; the passthrough composer
 * supplies the source config's original SOCKS tag so its untouched
 * `routing.rules[].inboundTag` references keep matching.
 * [credentials] is the only account the inbound accepts (M9-E spec §5.3): loopback is not a
 * boundary on Android, since any app on the device can reach `127.0.0.1`.
 *
 * Returns the inbound object with no trailing comma and no surrounding array;
 * the caller places it.
 */
internal fun socksInboundJson(
    port: Int,
    sniffing: SniffingSettings?,
    credentials: ProxyCredentials,
    tag: String = "socks-in",
): String = socksInboundJson(port, sniffing, preservedSniffing = null, credentials, tag)

/** The same loopback inbound with a passthrough config's sniffing object kept unchanged. */
internal fun socksInboundJsonPreservingSniffing(
    port: Int,
    sniffing: JsonObject,
    credentials: ProxyCredentials,
    tag: String,
): String = socksInboundJson(port, generatedSniffing = null, preservedSniffing = sniffing, credentials, tag)

private fun socksInboundJson(
    port: Int,
    generatedSniffing: SniffingSettings?,
    preservedSniffing: JsonObject?,
    credentials: ProxyCredentials,
    tag: String,
): String {
    val hasSniffing = generatedSniffing != null || preservedSniffing != null
    val sb = StringBuilder()
    sb.appendLine("""    {""")
    sb.appendLine("""      "tag": ${jsonString(tag)},""")
    sb.appendLine("""      "protocol": "socks",""")
    sb.appendLine("""      "listen": "127.0.0.1",""")
    sb.appendLine("""      "port": $port,""")
    sb.appendLine("""      "settings": {""")
    // `auth` must be exactly "password": any other value, or none, silently becomes NO_AUTH and the
    // accounts below are ignored (Xray-core v26.10.10 `infra/conf/socks.go:41-48`).
    sb.appendLine("""        "auth": "password",""")
    appendAccounts(sb, credentials, trailingComma = true)
    sb.appendLine("""        "udp": true""")
    sb.appendLine("""      }${if (hasSniffing) "," else ""}""")
    when {
        preservedSniffing != null -> sb.appendLine("""      "sniffing": $preservedSniffing""")
        generatedSniffing != null -> {
            val overrides = generatedSniffing.destOverride.joinToString(", ") { jsonString(it) }
            sb.appendLine("""      "sniffing": {""")
            sb.appendLine("""        "enabled": true,""")
            sb.appendLine("""        "destOverride": [$overrides]""")
            sb.appendLine("""      }""")
        }
    }
    sb.append("""    }""")
    return sb.toString()
}

/**
 * The inbound `:core:network` dials so app fetches travel through the tunnel.
 *
 * Same loopback rule, and it matters more here: an HTTP proxy reachable from the
 * LAN is usable directly from any browser on the network. No `sniffing` block —
 * an HTTP `CONNECT` states its destination, so there is nothing to sniff.
 * [tag] follows the same preservation rule as [socksInboundJson]; [credentials] is the same
 * session account, sent by clients as `Proxy-Authorization: Basic`.
 *
 * Returns the inbound object with no trailing comma and no surrounding array;
 * the caller places it.
 */
internal fun httpInboundJson(
    port: Int,
    credentials: ProxyCredentials,
    tag: String = "http-in",
): String {
    val sb = StringBuilder()
    sb.appendLine("""    {""")
    sb.appendLine("""      "tag": ${jsonString(tag)},""")
    sb.appendLine("""      "protocol": "http",""")
    sb.appendLine("""      "listen": "127.0.0.1",""")
    sb.appendLine("""      "port": $port,""")
    // No `auth` key on this inbound: accounts alone enforce Basic proxy auth
    // (Xray-core v26.10.10 `infra/conf/http.go:32-48`, `proxy/http/server.go:121`).
    sb.appendLine("""      "settings": {""")
    appendAccounts(sb, credentials, trailingComma = false)
    sb.appendLine("""      }""")
    sb.append("""    }""")
    return sb.toString()
}

/** The single-account `accounts` array both inbounds share; `{user, pass}` in both schemas. */
private fun appendAccounts(
    sb: StringBuilder,
    credentials: ProxyCredentials,
    trailingComma: Boolean,
) {
    sb.appendLine("""        "accounts": [""")
    sb.appendLine("""          {""")
    sb.appendLine("""            "user": ${jsonString(credentials.user)},""")
    sb.appendLine("""            "pass": ${jsonString(credentials.pass)}""")
    sb.appendLine("""          }""")
    sb.appendLine("""        ]${if (trailingComma) "," else ""}""")
}
