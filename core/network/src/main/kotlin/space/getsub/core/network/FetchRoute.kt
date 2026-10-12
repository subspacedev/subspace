// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network

import space.getsub.core.model.FragmentSpec

/**
 * How one fetch reaches its server (M9-B spec §3.1): fronting ([resolveAddress] for the TCP target,
 * [hostHeader] for the HTTP `Host`) and fetch fragmentation. The TLS name and the certificate check
 * always stay on the URL's own host. Addresses are secrets (ARCHITECTURE.md §5.6).
 */
public data class FetchRoute(
    val resolveAddress: String? = null,
    val hostHeader: String? = null,
    val fragment: FragmentSpec? = null,
) {
    override fun toString(): String =
        "FetchRoute(resolveAddress=${if (resolveAddress == null) "none" else "<redacted>"}, " +
            "hostHeader=${if (hostHeader == null) "none" else "<redacted>"}, fragment=${fragment != null})"
}
