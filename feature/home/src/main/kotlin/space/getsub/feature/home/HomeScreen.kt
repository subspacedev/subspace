// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.getsub.core.model.StartupStage
import space.getsub.core.ui.component.FLOATING_NAV_CONTENT_BOTTOM_PADDING
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
 *   server" button in every state.
 * @param onOpenRouting opens the routing rule-set list (the Routing chip).
 * @param onOpenPerApp opens per-app selection (the Apps chip).
 * @param onOpenSessionLog opens the session log (the history icon).
 */
// The six navigation callbacks are the screen's whole outward surface; HomeActions
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
 * site while a parameter list eight deep would not.
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

        StatTilesRow(state = state, onTest = actions.onTestLatency)

        HomeThroughputCard(state = state)

        ByServerCard(state = state)

        ActiveServerCard(
            state = state,
            onNavigateToServers = actions.onNavigateToServers,
            onAddServer = actions.onAddServer,
        )

        OutlinedButton(onClick = actions.onAddServer) {
            Icon(painterResource(SubspaceIcons.Add), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.home_add_server))
        }
    }
}
