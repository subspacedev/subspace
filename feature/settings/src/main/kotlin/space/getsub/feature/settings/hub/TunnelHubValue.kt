// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.hub

import androidx.annotation.StringRes
import space.getsub.core.model.ProxyAuthMode
import space.getsub.feature.settings.R
import space.getsub.feature.settings.SettingsState

/** The Tunnel row's value (M9-E): [Default] while nothing on the page overrides anything. */
internal enum class TunnelHubValue { Default, Custom }

/** Default when every override switch is off and proxy auth is Auto; values kept under an off switch don't count. */
internal fun SettingsState.tunnelValue(): TunnelHubValue {
    val t = tunnel
    val anyOverride =
        t.sniffing.override || t.mux.override || t.mtu.override || t.routes.override ||
            t.proxyAuth.mode != ProxyAuthMode.Auto
    return if (anyOverride) TunnelHubValue.Custom else TunnelHubValue.Default
}

@StringRes
internal fun tunnelTextRes(state: SettingsState): Int =
    when (state.tunnelValue()) {
        TunnelHubValue.Default -> R.string.settings_tunnel_hub_default
        TunnelHubValue.Custom -> R.string.settings_tunnel_hub_custom
    }
