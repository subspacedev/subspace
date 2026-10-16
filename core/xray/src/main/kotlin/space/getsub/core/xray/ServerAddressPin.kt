// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

/** The pre-resolved address for the proxy outbound (M9-C spec §3). [domain] fills address-derived fields. */
public data class ServerAddressPin(
    val domain: String,
    val ip: String,
) {
    override fun toString(): String = "ServerAddressPin(<redacted>)"
}
