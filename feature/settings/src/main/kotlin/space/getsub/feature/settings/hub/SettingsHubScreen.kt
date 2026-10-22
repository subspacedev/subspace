// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.hub

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.getsub.core.ui.component.FLOATING_NAV_CONTENT_BOTTOM_PADDING
import space.getsub.core.ui.component.HubRow
import space.getsub.core.ui.component.SectionTitle
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.SubspaceShapeTokens
import space.getsub.feature.settings.R
import space.getsub.feature.settings.SettingsActions
import space.getsub.feature.settings.SettingsNavigation
import space.getsub.feature.settings.SettingsPageId
import space.getsub.feature.settings.SettingsState
import space.getsub.feature.settings.SettingsViewModel
import space.getsub.feature.settings.TunnelSystemEffects
import space.getsub.feature.settings.labelRes
import space.getsub.feature.settings.openBatterySettings
import space.getsub.feature.settings.settingsActionsOf

/**
 * The Settings hub (M8.6 spec §4.4): You, Traffic, the Tunnel card inline (spec §5.1, fix A10), then
 * Connection, Diagnostics and About. The order is the owner's (2026-10-11); spec §4.4 put Traffic
 * first and the Tunnel card last. Rows show their current value and open a sub-page.
 *
 * Settings has no `NavController` of its own; every callback is forwarded verbatim to
 * `SubspaceNavHost`.
 *
 * @param onNavigateToRouting the Routing row: `RoutingList`.
 * @param onNavigateToPerApp the Per-app proxy row: `PerApp`.
 * @param onNavigateToLogViewer the Session log row: `LogViewer`.
 * @param onOpenPage every other row: `SettingsPage(page)` (M8.6 plan ruling R1).
 */
@Composable
fun SettingsScreen(
    onNavigateToRouting: () -> Unit,
    onNavigateToPerApp: () -> Unit,
    onNavigateToLogViewer: () -> Unit,
    onOpenPage: (SettingsPageId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel) { settingsActionsOf(viewModel) }
    SettingsHubContent(
        state = state,
        actions = actions,
        navigation =
        SettingsNavigation(
            onNavigateToRouting = onNavigateToRouting,
            onNavigateToPerApp = onNavigateToPerApp,
            onNavigateToLogViewer = onNavigateToLogViewer,
            onOpenPage = onOpenPage,
        ),
        modifier = modifier,
    )
}

/** The stateless half — see [space.getsub.feature.home.HomeScreenContent]'s KDoc for why this split exists. */
@Composable
internal fun SettingsHubContent(
    state: SettingsState,
    actions: SettingsActions,
    navigation: SettingsNavigation,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val open = navigation.onOpenPage
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            // The hub scrolls, so it — not FloatingNavBar — reserves the space the pill floats
            // over. See FLOATING_NAV_CONTENT_BOTTOM_PADDING's own KDoc. The pill also sits above the
            // navigation bar, so that inset is added: the constant alone left the last row under it.
            .padding(
                top = 16.dp,
                bottom = FLOATING_NAV_CONTENT_BOTTOM_PADDING + navigationBarInset,
            ),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        // Owner's order (2026-10-11): You, Traffic, Tunnel, then the rest as the M8.6 spec §4.4 had them.
        HubSection(R.string.settings_section_you, SubspaceShapeTokens.card) {
            val theme = stringResource(state.appearanceValue().labelRes())
            PageRow(SubspaceIcons.Palette, SettingsPageId.Appearance, theme, open)
            PageRow(SubspaceIcons.Fingerprint, SettingsPageId.DeviceId, deviceIdText(state), open)
        }

        HubSection(R.string.settings_section_traffic, SubspaceShapeTokens.cardLead) {
            HubRow(
                icon = SubspaceIcons.AltRoute,
                label = stringResource(R.string.settings_section_routing),
                value = routingText(state),
                onClick = navigation.onNavigateToRouting,
            )
            HubRow(
                icon = SubspaceIcons.Apps,
                label = stringResource(R.string.settings_per_app_row_label),
                value = perAppText(state),
                onClick = navigation.onNavigateToPerApp,
            )
            PageRow(SubspaceIcons.Public, SettingsPageId.Geo, joined(state.geoValue()), open)
        }

        HubTunnelCard(state, actions, context)

        HubSection(R.string.settings_section_connection, SubspaceShapeTokens.card) {
            PageRow(SubspaceIcons.Speed, SettingsPageId.Latency, latencyText(state), open)
            PageRow(SubspaceIcons.Dns, SettingsPageId.Dns, dnsText(state), open)
            PageRow(SubspaceIcons.Shield, SettingsPageId.Censorship, stringResource(censorshipTextRes(state)), open)
            PageRow(SubspaceIcons.Tune, SettingsPageId.Tunnel, stringResource(tunnelTextRes(state)), open)
        }

        SectionTitle(stringResource(R.string.settings_section_diagnostics), Modifier.padding(horizontal = 4.dp))
        DiagnosticsGroup(
            state = state,
            onOpenSessionLog = navigation.onNavigateToLogViewer,
            onPerTagBreakdownChange = actions.onPerTagBreakdownChanged,
        )

        HubSection(R.string.settings_section_about, SubspaceShapeTokens.cardTail) {
            PageRow(SubspaceIcons.Info, SettingsPageId.About, aboutText(state), open)
        }
    }

    TunnelSystemEffects(state = state, actions = actions, context = context)
}

/** A row that opens one of the sub-pages; its label is the page's own title. */
@Composable
private fun PageRow(
    @DrawableRes icon: Int,
    page: SettingsPageId,
    value: String,
    open: (SettingsPageId) -> Unit,
) {
    HubRow(icon = icon, label = stringResource(page.titleRes), value = value, onClick = { open(page) })
}

@Composable
private fun HubSection(
    @StringRes titleRes: Int,
    shape: Shape,
    content: @Composable ColumnScope.() -> Unit,
) {
    SectionTitle(stringResource(titleRes), Modifier.padding(horizontal = 4.dp))
    HubGroup(shape = shape, content = content)
}

/** A `surfaceContainer` card holding one hub group's rows. */
@Composable
internal fun HubGroup(
    modifier: Modifier = Modifier,
    shape: Shape = SubspaceShapeTokens.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = shape, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), content = content)
    }
}

/** The Tunnel card, with its title, wired to the view model and to battery settings. */
@Composable
private fun HubTunnelCard(
    state: SettingsState,
    actions: SettingsActions,
    context: Context,
) {
    SectionTitle(stringResource(R.string.settings_tunnel_title), Modifier.padding(horizontal = 4.dp))
    TunnelCard(
        state = state,
        actions =
        TunnelCardActions(
            onBootAutostartChange = actions.onBootAutostartChanged,
            onFailClosedChange = actions.onFailClosedChanged,
            onLightweightModeChange = actions.onLightweightModeChanged,
            // M8.5 spec §6 #10: the ViewModel decides whether a battery prompt comes first;
            // the deep link is launched from openVpnSettingsRequested in TunnelSystemEffects,
            // never directly, so the prompt cannot be buried under the system VPN screen.
            onOpenVpnSettings = actions.onAlwaysOnRequested,
            onOpenBatterySettings = { openBatterySettings(context) },
        ),
    )
}
