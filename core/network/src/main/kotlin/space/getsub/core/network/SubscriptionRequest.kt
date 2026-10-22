// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network

import space.getsub.core.model.LocalProxy
import space.getsub.core.model.identity.DeviceHeader

/**
 * Everything [SubscriptionFetcher] needs to perform one subscription fetch.
 *
 * @property url the subscription URL. A secret (ARCHITECTURE.md §5.6) — never logged.
 * @property hwid this subscription's own `x-hwid` (M10-B spec §3.5), or null to send none. Off
 *   is a user choice that breaks limit-enabled providers, and the resulting failure must say so
 *   ([FetchFailure.HwidRequired]). A secret (ARCHITECTURE.md §5.6) — never logged.
 * @property headers the device headers to send, already resolved and made safe to send by
 *   `:core:data`. Exactly these are sent: a header absent from the map is not sent at all
 *   (M10-B spec §3.5). The values are never logged.
 * @property userAgentOverride §A.4.2's per-subscription override.
 * @property timeoutSeconds documented range 5–15, default 9. Supplied by the
 *   caller because it arrives as a directive delivered *by* the fetch it
 *   configures — the previous fetch's value, or the default on first contact.
 * @property proxy the tunnel's loopback HTTP proxy and the session's account for it (M9-E
 *   spec §5.3), or null to fetch directly. Defaults to null so every pre-existing call site keeps
 *   the old behaviour unchanged.
 * @property route M9-B: fronting and fetch fragmentation; see [FetchRoute].
 */
public data class SubscriptionRequest(
    val url: String,
    val hwid: String?,
    val headers: Map<DeviceHeader, String>,
    val userAgentOverride: String?,
    val timeoutSeconds: Int,
    val proxy: LocalProxy? = null,
    val route: FetchRoute = FetchRoute(),
) {
    // ARCHITECTURE.md §5.6: url is a secret. The generated data-class toString() would print
    // it verbatim, which is exactly the "reaches a log line" failure mode
    // this class exists to avoid — this is a structural guard against a
    // future `request.toString()` in a debug log, not a fix for a leak that
    // exists today. The other fields are not secrets and stay visible: they
    // are what makes a logged instance useful for debugging at all.
    //
    // hwid and the header values identify the device (ARCHITECTURE.md §5.6, M10-B constraints), so
    // only whether an HWID is sent and which headers are sent are printed.
    //
    // proxy is deliberately not printed either. Its port is not a secret (ARCHITECTURE.md §5.6),
    // but it carries the session's proxy credentials (M9-E spec §5.3), which are; ProxyCredentials
    // redacts itself, and leaving the whole field off keeps that from resting on one override.
    override fun toString(): String =
        "SubscriptionRequest(url=<redacted>, hwid=${if (hwid == null) "none" else "<redacted>"}, " +
            "headers=${headers.keys.map { it.wireName }}, " +
            "userAgentOverride=$userAgentOverride, timeoutSeconds=$timeoutSeconds)"
}
