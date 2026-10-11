// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.hub

/** The Tunnel card's callbacks: three switches and two system-settings links. */
internal data class TunnelCardActions(
    val onBootAutostartChange: (Boolean) -> Unit,
    val onFailClosedChange: (Boolean) -> Unit,
    val onLightweightModeChange: (Boolean) -> Unit,
    /**
     * Asks to open `Settings.ACTION_VPN_SETTINGS`. This app cannot enable always-on VPN or its
     * "Block connections without VPN" lockdown itself — both are system settings only the user (or
     * a device policy owner) can set (M8 spec §7.1). A switch would claim ownership of state this
     * app does not have; a link that opens the real control is the only honest affordance.
     */
    val onOpenVpnSettings: () -> Unit,
    /** Opens `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (the list, not a direct request). */
    val onOpenBatterySettings: () -> Unit,
)
