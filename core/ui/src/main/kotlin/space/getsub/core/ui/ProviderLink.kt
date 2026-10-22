// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens a provider-supplied link in whatever handles it, on the user's tap (M10-B spec §3.1).
 *
 * Callers pass only `SubscriptionInfo` link fields, which `safeLink` has already limited to
 * `http`/`https`. A device with no browser must not crash the app, so a missing activity is
 * swallowed. The URL is never logged (ARCHITECTURE.md §5.6).
 */
fun Context.openProviderLink(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Nothing can open it; the button simply does nothing.
    }
}
