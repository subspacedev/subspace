// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.StartupStage
import space.getsub.core.ui.component.FLOATING_NAV_CONTENT_BOTTOM_PADDING
import space.getsub.core.ui.component.StatTile
import space.getsub.core.ui.format.formatByteCount
import space.getsub.core.ui.icon.SubspaceIcons

/**
 * The connect screen: what M3 replaces M1's paste-a-link walking skeleton
 * with.
 *
 * Connects to the profile [space.getsub.core.data.SettingsRepository.activeProfileId]
 * names, never `ProfileRepository`'s first row — retiring the M1 shortcut
 * where pasting a 200-server subscription silently connected to entry #1 with
 * no sign the other 199 existed. Import and the paste field live in
 * `:feature:profiles` now (Task 19); this screen only ever reads.
 *
 * @param onRequestConsent must launch `VpnService.prepare` and invoke its
 *   callback only on approval. A ViewModel cannot start an activity for a
 *   result, and without consent `establish()` returns null and the start
 *   sequence fails at [StartupStage.EstablishingTun] — which looks like a bug
 *   rather than a missing permission.
 * @param onNavigateToServers invoked when the active-server tile is tapped
 *   and at least one profile already exists — the server list is where the
 *   user picks which one is active.
 * @param onAddServer invoked when the active-server tile is tapped and no
 *   profile exists yet (nothing to pick from), and by the standalone "Add
 *   server" chip in every state.
 * @param onOpenRouting opens the routing rule-set list (the Routing chip).
 * @param onOpenPerApp opens per-app selection (the Apps chip).
 * @param onOpenSessionLog opens the session log (the history icon).
 */
// The three navigation callbacks are the screen's whole outward surface; HomeActions
// carries them past this point (see its KDoc).
@Suppress("LongParameterList")
@Composable
fun HomeScreen(
    onRequestConsent: (onGranted: () -> Unit) -> Unit,
    onNavigateToServers: () -> Unit,
    onAddServer: () -> Unit,
    onOpenRouting: () -> Unit,
    onOpenPerApp: () -> Unit,
    onOpenSessionLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: HomeViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Keyed on the active profile: switching servers should measure the new one,
    // and onHomeShown is a no-op for a profile that already has a reading.
    LaunchedEffect(state.activeProfile?.id) { viewModel.onHomeShown() }

    HomeScreenContent(
        state = state,
        actions =
        HomeActions(
            onConnect = { onRequestConsent(viewModel::onConsentGranted) },
            onDisconnect = viewModel::onDisconnect,
            onNavigateToServers = onNavigateToServers,
            onAddServer = onAddServer,
            onTestLatency = viewModel::onTestLatency,
            onOpenRouting = onOpenRouting,
            onOpenPerApp = onOpenPerApp,
            onOpenSessionLog = onOpenSessionLog,
        ),
        modifier = modifier,
    )
}

/**
 * [HomeScreenContent]'s callbacks, grouped into one carrier.
 *
 * A real detekt `LongParameterList` signal, not a suppression candidate —
 * same call [ClashYaml][space.getsub.core.parser]'s `ClashCommon`
 * makes for the same rule: a carrier keeps the callbacks named at every call
 * site while a parameter list six deep would not.
 */
internal data class HomeActions(
    val onConnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val onNavigateToServers: () -> Unit,
    val onAddServer: () -> Unit,
    /**
     * M4.5: measures the active profile. The only other trigger is ping-on-launch,
     * once per session — nothing measures on connect and nothing on a timer.
     */
    val onTestLatency: () -> Unit,
    /** Opens the routing rule-set list. */
    val onOpenRouting: () -> Unit,
    /** Opens per-app selection. */
    val onOpenPerApp: () -> Unit,
    /** Opens the session log. */
    val onOpenSessionLog: () -> Unit,
)

/**
 * The stateless half.
 *
 * Split out so the screen can be rendered from a state value alone — the
 * ViewModel stays internal to this module, and a future Compose UI test gets
 * something it can drive without Hilt.
 */
@Composable
internal fun HomeScreenContent(
    state: HomeState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            // Home scrolls, so it — not FloatingNavBar — is
            // responsible for reserving the space the pill floats over.
            // See FLOATING_NAV_CONTENT_BOTTOM_PADDING's own KDoc.
            .padding(top = 24.dp, bottom = FLOATING_NAV_CONTENT_BOTTOM_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        HomeTopBar(actions.onOpenSessionLog)

        HomeHero(state, actions, nowMillis)

        HomeChips(state, actions)

        ActiveServerTile(
            state = state,
            onNavigateToServers = actions.onNavigateToServers,
            onAddServer = actions.onAddServer,
        )

        StatTilesRow(state = state, onTest = actions.onTestLatency)

        BreakdownSection(state = state)

        AssistChip(
            onClick = actions.onAddServer,
            label = { Text(stringResource(R.string.home_add_server)) },
            leadingIcon = {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
            },
        )
    }
}

/**
 * The current server, or a prompt to pick or add one.
 *
 * Tapping goes to [onNavigateToServers] whenever at least one profile exists
 * ([HomeState.hasAnyProfile]) — that is where a server is chosen or its
 * active status changed — and to [onAddServer] only when the store is
 * genuinely empty, since there is nothing to navigate to and pick from yet.
 */
/**
 * The design system's [StatTile] row: LATENCY, and — for a live session —
 * DOWN/UP beside it.
 *
 * `StatTile`'s own KDoc asked that whichever milestone first has a real value to
 * show be the one to render it. M4.5 is that milestone for latency; M8.5 is the
 * same for traffic, from `hev-socks5-tunnel`'s own TUN-level counters — not the
 * Xray stats API, which does not exist at this project's pinned libXray version.
 *
 * LATENCY: every non-`OK` outcome renders text rather than a number.
 * `delayMillis` is read on the `OK` branch alone: libXray reports a failed ping
 * as a `10000`/`11000` sentinel, and `StatTile` formats nothing itself — it
 * renders exactly what it is handed, which makes formatting the caller's
 * responsibility and this branch the place M8.5 spec §10.1 is either honoured or
 * violated. Tapping measures, and so does ping-on-launch once per session.
 * Nothing else: no measurement on connect, and no timer.
 *
 * DOWN/UP: rendered only while [HomeState.connection] is
 * [ConnectionState.Connected] and [HomeState.trafficHidden] (lightweight mode) is
 * off — never on [HomeState.traffic] alone, because an
 * ordinary in-session disconnect while the UI stays bound does not clear
 * `TunnelClient`'s cached sample (see [TunnelConnection.traffic]'s KDoc).
 * [formatByteCount] does the formatting, same as [HomeState.latency]'s branch
 * above; `0 B` for no traffic yet is a real measurement, not a placeholder, so
 * unlike latency there is no em-dash variant here (M8.5 spec §10.1). Not tappable: there
 * is no on-demand action for traffic the way there is for a latency test, only
 * the passive per-second stream [HomeViewModel] mirrors into
 * [HomeState.traffic].
 */
@Composable
private fun StatTilesRow(
    state: HomeState,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latency = state.latency
    val latencyValue =
        when {
            state.isMeasuringLatency -> stringResource(R.string.home_latency_testing)
            latency == null -> stringResource(R.string.home_latency_none)
            latency.outcome == LatencyOutcome.OK -> stringResource(R.string.home_latency_ms, latency.delayMillis)
            latency.outcome == LatencyOutcome.UNSUPPORTED -> stringResource(R.string.home_latency_unsupported)
            latency.outcome == LatencyOutcome.FOREIGN_VPN -> stringResource(R.string.home_latency_foreign_vpn)
            else -> stringResource(R.string.home_latency_failed)
        }
    val latencyHint = stringResource(R.string.home_latency_test_description)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatTile(
            icon = SubspaceIcons.NetworkPing,
            value = latencyValue,
            label = stringResource(R.string.home_latency_label),
            modifier = Modifier.weight(1f),
            // Disabled, not action-less, while a test runs or no server is
            // chosen: TalkBack then says the test exists but cannot run now.
            onClick = onTest,
            enabled = state.activeProfile != null && !state.isMeasuringLatency,
            actionDescription = latencyHint,
        )
        if (state.connection is ConnectionState.Connected && !state.trafficHidden) {
            StatTile(
                icon = SubspaceIcons.SouthEast,
                value = formatByteCount(state.traffic?.downlinkBytes ?: 0),
                label = stringResource(R.string.home_traffic_down),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                icon = SubspaceIcons.NorthEast,
                value = formatByteCount(state.traffic?.uplinkBytes ?: 0),
                label = stringResource(R.string.home_traffic_up),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The active server, and where tapping it goes — see [HomeActions] for the split
 * between navigating to the list and going straight to import.
 */
@Composable
private fun ActiveServerTile(
    state: HomeState,
    onNavigateToServers: () -> Unit,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeProfile = state.activeProfile
    val headline: String
    val actionDescription: String
    val onClick: () -> Unit
    when {
        activeProfile != null -> {
            headline = activeProfile.name
            actionDescription = stringResource(R.string.home_active_server_change_description)
            onClick = onNavigateToServers
        }

        state.hasAnyProfile -> {
            headline = stringResource(R.string.home_choose_a_server)
            actionDescription = headline
            onClick = onNavigateToServers
        }

        else -> {
            headline = stringResource(R.string.home_no_servers_yet)
            actionDescription = stringResource(R.string.home_add_first_server)
            onClick = onAddServer
        }
    }

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = actionDescription },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.home_active_server_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = headline, style = MaterialTheme.typography.titleMedium)
        }
    }
}
