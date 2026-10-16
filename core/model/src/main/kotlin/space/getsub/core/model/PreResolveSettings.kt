// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

/** The user's server pre-resolution (M9-C spec §1.4). Stored as typed; validated where used. */
public data class PreResolveSettings(
    val enabled: Boolean,
    val dohUrl: String,
    val bootstrapIp: String,
) {
    public companion object {
        public val DEFAULT: PreResolveSettings =
            PreResolveSettings(
                enabled = false,
                dohUrl = "https://cloudflare-dns.com/dns-query",
                bootstrapIp = "1.1.1.1",
            )
    }
}
