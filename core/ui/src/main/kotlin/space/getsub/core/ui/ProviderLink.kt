// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI

/**
 * Whether [url] is something [openProviderLink] may hand to the system: an `http` or `https` URL
 * with a host. Plain JVM so it can be tested without a device.
 */
internal fun isOpenableProviderLink(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
}

/**
 * Opens a provider-supplied link in whatever handles it, on the user's tap (M10-B spec §3.1).
 *
 * Callers pass only `SubscriptionInfo` link fields, which `safeLink` has already limited to
 * `http`/`https`; this checks again, so a caller that forgets cannot launch an `intent:` or
 * `file:` URL. A device with no browser must not crash the app, so a missing activity is
 * swallowed. The URL is never logged (ARCHITECTURE.md §5.6).
 */
fun Context.openProviderLink(url: String) {
    if (!isOpenableProviderLink(url)) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Nothing can open it; the button simply does nothing.
    }
}
