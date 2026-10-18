// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import space.getsub.core.model.DnsState
import space.getsub.core.model.ProfileDns

/**
 * Maps a typed profile DNS block to its visible state.
 *
 * FakeDNS is never refused for want of sniffing: with sniffing off the tunnel keeps a
 * `fakedns`-only sniffing block (M9-E spec §2), so a FakeDNS request is simply applied.
 */
internal fun dnsStateOf(dns: ProfileDns?): DnsState =
    when {
        dns == null -> DnsState.None
        dns.isInvalid -> DnsState.Invalid
        else -> DnsState.Applied
    }
