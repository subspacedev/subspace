// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import java.net.URI

private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")
private val IPV6 = Regex("""^[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*$""")

/** Early feedback only (M9-C spec §1.4); the connect-time policy re-validates strictly. */
internal fun isIpLiteral(s: String): Boolean = IPV4.matches(s.trim()) || IPV6.matches(s.trim())

internal fun isDohUrl(s: String): Boolean =
    runCatching { URI(s.trim()) }.getOrNull()?.let { uri ->
        uri.scheme == "https" && !uri.host.isNullOrBlank() && !isIpLiteral(uri.host) && uri.userInfo == null
    } == true
