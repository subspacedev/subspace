// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import androidx.annotation.StringRes
import space.getsub.core.data.TuningValue
import space.getsub.core.model.TuningOrigin
import space.getsub.core.model.TuningProblem
import space.getsub.feature.settings.R

/**
 * Which sentence [providerLine] uses, and the reason it appends when the subscription's value was
 * ignored, wholly ([partial] false: the default applies) or in part (a list with ranges dropped).
 */
internal data class ProviderLineParts(
    @param:StringRes val res: Int,
    val ignored: TuningProblem? = null,
    val partial: Boolean = false,
)

internal fun providerLineParts(value: TuningValue<*>?): ProviderLineParts {
    val ignored = value?.ignored
    val fromSubscription = value?.origin == TuningOrigin.Subscription
    return when {
        value == null -> ProviderLineParts(R.string.settings_tunnel_no_server)
        ignored == null && fromSubscription -> ProviderLineParts(R.string.settings_tunnel_from_subscription)
        ignored == null -> ProviderLineParts(R.string.settings_tunnel_default_value)
        fromSubscription ->
            ProviderLineParts(R.string.settings_tunnel_from_subscription_ignored, ignored, partial = true)
        else -> ProviderLineParts(R.string.settings_tunnel_default_ignored, ignored, partial = false)
    }
}
