// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import space.getsub.core.data.ThemePreference
import space.getsub.core.model.DnsTransport
import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.GeoDataKind
import space.getsub.core.model.NoiseSettings
import space.getsub.core.model.PingMode
import space.getsub.core.model.PreResolveSettings
import space.getsub.core.ui.component.SubspaceDialog
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.feature.settings.pages.TunnelDraft

/**
 * The hub's cross-module navigation callbacks, grouped so the hub takes one parameter for them.
 * Settings has no `NavController` of its own: each callback is forwarded verbatim to
 * `SubspaceNavHost`.
 *
 * @param onOpenPage opens one of the hub's sub-pages (`SettingsPage(page)` in `:app`, M8.6 plan
 *   ruling R1).
 */
internal data class SettingsNavigation(
    val onNavigateToRouting: () -> Unit,
    val onNavigateToPerApp: () -> Unit,
    val onNavigateToLogViewer: () -> Unit,
    val onOpenPage: (SettingsPageId) -> Unit,
)

/**
 * The Settings screens' callbacks, grouped for the same reason
 * [HomeActions][space.getsub.feature.home.HomeActions] is.
 *
 * A carrier only became worth it with M4.5: two callbacks were fine as
 * parameters, seven would be a `LongParameterList` finding and would leave every
 * call site positional.
 */
internal data class SettingsActions(
    val onThemeChanged: (ThemePreference) -> Unit,
    val onPingModeChanged: (PingMode) -> Unit,
    val onPingCheckUrlChanged: (String) -> Unit,
    val onPingTimeoutChanged: (Int) -> Unit,
    val onPingOnLaunchChanged: (Boolean) -> Unit,
    val onPingOnLaunchMeteredChanged: (Boolean) -> Unit,
    val onDnsTransportChanged: (DnsTransport) -> Unit,
    val onDnsAddressChanged: (String) -> Unit,
    val onDnsBootstrapIpChanged: (String) -> Unit,
    val onGeoSourceSelected: (String) -> Unit,
    val onGeoUpdateNow: (GeoRow) -> Unit,
    val onAddCustomGeoSource: (url: String, fileName: String, geoType: GeoDataKind) -> Unit,
    val onGeoRefreshOnMeteredChanged: (Boolean) -> Unit,
    val onRemoveCustomGeoSource: (String) -> Unit,
    // Defaulted (unlike every field above): these landed after SettingsDnsSectionTest's own
    // full positional SettingsActions(...) construction, and none of them bears on what it asserts.
    val onBootAutostartChanged: (Boolean) -> Unit = {},
    val onFailClosedChanged: (Boolean) -> Unit = {},
    val onBatteryPromptResolved: () -> Unit = {},
    // M8 spec §7.2's third battery-prompt trigger: always-on is a deep link, not a switch, so the
    // tap is what the app can observe.
    val onAlwaysOnRequested: () -> Unit = {},
    // M8.5 spec §6 #10: consumes SettingsState.openVpnSettingsRequested once the screen has
    // launched the deep link it names.
    val onVpnSettingsOpened: () -> Unit = {},
    val onPerTagBreakdownChanged: (Boolean) -> Unit = {},
    val onLightweightModeChanged: (Boolean) -> Unit = {},
    val onDynamicColorChanged: (Boolean) -> Unit = {},
    // Field-level patches, applied by the ViewModel to the latest stored value (M9-A Task 8 fix 1).
    val onFragmentSettingsChanged: ((FragmentSettings) -> FragmentSettings) -> Unit = {},
    val onNoiseSettingsChanged: ((NoiseSettings) -> NoiseSettings) -> Unit = {},
    val onPreResolveSettingsChanged: ((PreResolveSettings) -> PreResolveSettings) -> Unit = {},
    val onTunnelSettingsSaved: (TunnelDraft) -> Unit = {},
)

/** Every [SettingsActions] callback bound to [viewModel]; shared by the hub and the sub-pages. */
internal fun settingsActionsOf(viewModel: SettingsViewModel): SettingsActions =
    SettingsActions(
        onThemeChanged = viewModel::onThemeChanged,
        onPingModeChanged = viewModel::onPingModeChanged,
        onPingCheckUrlChanged = viewModel::onPingCheckUrlChanged,
        onPingTimeoutChanged = viewModel::onPingTimeoutChanged,
        onPingOnLaunchChanged = viewModel::onPingOnLaunchChanged,
        onPingOnLaunchMeteredChanged = viewModel::onPingOnLaunchMeteredChanged,
        onDnsTransportChanged = viewModel::onDnsTransportChanged,
        onDnsAddressChanged = viewModel::onDnsAddressChanged,
        onDnsBootstrapIpChanged = viewModel::onDnsBootstrapIpChanged,
        onGeoSourceSelected = viewModel::onGeoSourceSelected,
        onGeoUpdateNow = viewModel::onGeoUpdateNow,
        onAddCustomGeoSource = viewModel::onAddCustomGeoSource,
        onGeoRefreshOnMeteredChanged = viewModel::onGeoRefreshOnMeteredChanged,
        onRemoveCustomGeoSource = viewModel::onRemoveCustomGeoSource,
        onBootAutostartChanged = viewModel::onBootAutostartChanged,
        onFailClosedChanged = viewModel::onFailClosedChanged,
        onBatteryPromptResolved = viewModel::onBatteryPromptResolved,
        onAlwaysOnRequested = viewModel::onAlwaysOnRequested,
        onVpnSettingsOpened = viewModel::onVpnSettingsOpened,
        onPerTagBreakdownChanged = viewModel::onPerTagBreakdownChanged,
        onLightweightModeChanged = viewModel::onLightweightModeChanged,
        onDynamicColorChanged = viewModel::onDynamicColorChanged,
        onFragmentSettingsChanged = viewModel::onFragmentSettingsChanged,
        onNoiseSettingsChanged = viewModel::onNoiseSettingsChanged,
        onPreResolveSettingsChanged = viewModel::onPreResolveSettingsChanged,
        onTunnelSettingsSaved = viewModel::onTunnelSettingsSaved,
    )

/**
 * The Tunnel card's system side: the always-on deep link and the battery-optimisation prompt
 * (Task 13, M8 spec §7.2). Moved unchanged from the old single Settings screen; the dialog is
 * restyled in M8.6 Part 4 Task 8.
 *
 * [SettingsState.showBatteryPrompt] is true for the one moment between a survival setting being
 * switched on and the user responding. Both the dialog's own dismiss and its "open battery
 * settings" action resolve through [SettingsActions.onBatteryPromptResolved] — ARCHITECTURE.md §9's
 * "respect refusal" means the prompt is marked seen whatever the user chooses, not only on
 * acceptance.
 */
@Composable
internal fun TunnelSystemEffects(
    state: SettingsState,
    actions: SettingsActions,
    context: Context,
) {
    // Controller ruling R14 (M8.5 spec §6 #10, final fix wave finding #1): the confirm path on
    // the battery dialog below starts the battery-optimisation list on top of this screen, which
    // moves this screen off RESUMED without stopping it — Compose keeps composing frames until
    // ON_STOP. Gating the deep link on RESUMED, rather than firing it the instant the ViewModel
    // requests it, is what stops it from launching a frame later and burying that battery screen
    // under ACTION_VPN_SETTINGS: the launch now waits for the user to come back from battery
    // settings (or, when no prompt is due, fires immediately, since the screen is resumed then
    // too).
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(state.openVpnSettingsRequested, lifecycleState) {
        if (shouldLaunchVpnSettings(state.openVpnSettingsRequested, lifecycleState)) {
            openVpnSettings(context)
            actions.onVpnSettingsOpened()
        }
    }

    if (state.showBatteryPrompt) {
        SubspaceDialog(
            icon = SubspaceIcons.BatterySaver,
            title = stringResource(R.string.settings_battery_prompt_title),
            body = stringResource(R.string.settings_battery_prompt_body),
            confirmLabel = stringResource(R.string.settings_battery_prompt_confirm),
            onConfirm = {
                actions.onBatteryPromptResolved()
                openBatterySettings(context)
            },
            dismissLabel = stringResource(R.string.settings_battery_prompt_dismiss),
            onDismiss = actions.onBatteryPromptResolved,
        )
    }
}

/** M8 spec §7.1: the app cannot set always-on VPN or its lockdown itself — both are system settings. */
private fun openVpnSettings(context: Context) {
    launchSettingsIntent(context, Settings.ACTION_VPN_SETTINGS)
}

/**
 * M8 spec §7.2: the settings *list*, not `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — that direct
 * prompt needs the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, one of the most
 * policy-sensitive on Android, and ARCHITECTURE.md §14.7 says nothing here should foreclose Google
 * Play. One extra tap is cheaper than that permission, and no new permission is declared for it.
 */
internal fun openBatterySettings(context: Context) {
    launchSettingsIntent(context, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}

/**
 * Both settings rows launch an implicit system intent this app does not control the target of —
 * [FLAG_ACTIVITY_NEW_TASK][Intent.FLAG_ACTIVITY_NEW_TASK] since [context] here is not itself an
 * `Activity`, and a caught [ActivityNotFoundException] rather than an uncaught crash for the rare
 * OEM build missing the target settings Activity: a row that only ever opens elsewhere must not be
 * able to take the whole settings screen down with it.
 */
private fun launchSettingsIntent(context: Context, action: String) {
    val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No target Activity on this device/build — nothing more this row can do.
    }
}
