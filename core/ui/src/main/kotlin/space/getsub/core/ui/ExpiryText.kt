// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import space.getsub.core.model.subscription.Expiry
import space.getsub.core.model.subscription.isSoon
import java.time.format.DateTimeFormatter

/**
 * The words for a provider's expiry: "expires 14 Nov", or from 3 days out "expires in N days",
 * "expires today", "expired" (M10-B spec §3.1). Shared by the Servers card and Home so the two
 * never drift apart.
 */
@Composable
fun expiryText(expiry: Expiry): String =
    when (expiry) {
        Expiry.None -> stringResource(R.string.subscription_no_expiry)
        is Expiry.On ->
            if (expiry.isSoon) {
                pluralStringResource(
                    R.plurals.subscription_expiry_in_days,
                    expiry.daysLeft.toInt(),
                    expiry.daysLeft.toInt(),
                )
            } else {
                val locale = LocalLocale.current.platformLocale
                val date = DateTimeFormatter.ofPattern("d MMM", locale).format(expiry.date)
                stringResource(R.string.subscription_expiry_on, date)
            }
        Expiry.Today -> stringResource(R.string.subscription_expiry_today)
        Expiry.Expired -> stringResource(R.string.subscription_expired)
    }
