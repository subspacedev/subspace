// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// The page id enum is the one declaration; its two composables are the screen it names.
@file:Suppress("MatchingDeclarationName")

package space.getsub.feature.settings

import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.getsub.feature.settings.pages.AboutPage
import space.getsub.feature.settings.pages.AppearancePage
import space.getsub.feature.settings.pages.CensorshipPage
import space.getsub.feature.settings.pages.DnsPage
import space.getsub.feature.settings.pages.GeoPage
import space.getsub.feature.settings.pages.LatencyPage
import space.getsub.feature.settings.pages.TunnelPage

/**
 * The Settings hub's sub-pages (M8.6 spec §4.4, plan ruling R1). Routing and Per-app have their own routes.
 *
 * [Keep] because `:app`'s `SettingsPage` route carries this enum, and navigation-compose finds an enum
 * argument's type by its class name at runtime. R8 renamed it, and the release build crashed on launch.
 */
@Keep
enum class SettingsPageId(
    @param:StringRes internal val titleRes: Int,
) {
    Appearance(R.string.settings_section_appearance),
    Latency(R.string.settings_latency_section),
    Dns(R.string.settings_dns_title),
    Censorship(R.string.settings_censorship_title),
    Tunnel(R.string.settings_tunnel_title),
    Geo(R.string.settings_section_geo),
    About(R.string.settings_section_about),
}

/**
 * One Settings sub-page, pushed from the hub as `SettingsPage(page)` (M8.6 plan ruling R1).
 *
 * @param viewModelStoreOwner the Settings tab's back-stack entry, so the hub and every page share
 *   one [SettingsViewModel] (controller ruling, Task 3 fix round 1). A page-scoped one would be
 *   cleared on Back, cancelling a geo download running in its `viewModelScope` and losing
 *   `geoUpdateInFlight`/`geoUpdateResults`.
 */
@Composable
fun SettingsPageScreen(
    page: SettingsPageId,
    viewModelStoreOwner: ViewModelStoreOwner,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = hiltViewModel(viewModelStoreOwner)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel) { settingsActionsOf(viewModel) }
    SettingsPageContent(page = page, state = state, actions = actions, onBack = onBack, modifier = modifier)
}

/** The stateless half of [SettingsPageScreen]: dispatches on [page]. */
@Composable
internal fun SettingsPageContent(
    page: SettingsPageId,
    state: SettingsState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (page) {
        SettingsPageId.Appearance -> AppearancePage(state, actions, onBack, modifier)
        SettingsPageId.Latency -> LatencyPage(state, actions, onBack, modifier)
        SettingsPageId.Dns -> DnsPage(state, actions, onBack, modifier)
        SettingsPageId.Censorship -> CensorshipPage(state, actions, onBack, modifier)
        SettingsPageId.Tunnel -> TunnelPage(state, actions, onBack, modifier)
        SettingsPageId.Geo -> GeoPage(state, actions, onBack, modifier)
        SettingsPageId.About -> AboutPage(state, onBack, modifier)
    }
}
