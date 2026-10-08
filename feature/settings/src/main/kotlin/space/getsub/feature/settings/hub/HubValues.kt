// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.hub

import space.getsub.core.data.ThemePreference
import space.getsub.core.model.DnsTransport
import space.getsub.core.model.PerAppMode
import space.getsub.core.model.PingMode
import space.getsub.feature.settings.SettingsState

/**
 * What a hub row's value says for the two rows whose value is a small state rather than a plain
 * field (M8.6 spec §4.4). One type serves both: [Active] and [Off] are Routing's, [Off],
 * [AllowList] and [DenyList] are Per-app's. [Off] is shared on purpose.
 */
internal sealed interface HubValue {
    /** An active routing rule set. [name] is displayed, never logged. */
    data class Active(val name: String) : HubValue

    data object Off : HubValue

    data class AllowList(val count: Int) : HubValue

    data class DenyList(val count: Int) : HubValue
}

/** The Session log row's value (M8.6 plan ruling R3: no line count, so the hub never reads the log). */
internal enum class SessionLogValue { Recording, NotRecordingLightweight }

internal fun SettingsState.routingValue(): HubValue = activeRuleSetName?.let(HubValue::Active) ?: HubValue.Off

/** From the **effective** selection, the one `:service` applies. */
internal fun SettingsState.perAppValue(): HubValue =
    when (perApp.mode) {
        PerAppMode.Off -> HubValue.Off
        PerAppMode.AllowList -> HubValue.AllowList(perApp.packages.size)
        PerAppMode.DenyList -> HubValue.DenyList(perApp.packages.size)
    }

internal fun SettingsState.appearanceValue(): ThemePreference = theme

/** True when the Device ID is sent to the provider. */
internal fun SettingsState.deviceIdValue(): Boolean = hwidEnabled

internal fun SettingsState.latencyValue(): Pair<PingMode, Int> = pingMode to pingTimeoutSeconds

internal fun SettingsState.dnsValue(): Pair<DnsTransport, String> = dnsTransport to dnsAddress

/** Installed asset file names; the source names (each once) while nothing is installed yet. */
internal fun SettingsState.geoValue(): List<String> =
    geoInstalledAssets.map { it.fileName }.ifEmpty { geoRows.map { it.displayName } }.distinct()

internal fun SettingsState.sessionLogValue(): SessionLogValue =
    if (lightweightMode) SessionLogValue.NotRecordingLightweight else SessionLogValue.Recording

internal fun SettingsState.killSwitchExtraLine(): Boolean = failClosed && perApp.mode != PerAppMode.Off

internal fun SettingsState.lightweightExtraLine(): Boolean = lightweightMode && sessionConnected
