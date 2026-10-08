// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.getsub.core.data.ThemePreference
import space.getsub.core.ui.component.SettingRow
import space.getsub.core.ui.icon.SubspaceIcons

/** Aligns the HWID value with [SettingRow]'s label column: its 40.dp icon tile plus a 16.dp gap. */
private val HWID_VALUE_START_PADDING = 56.dp
private val HWID_VALUE_BOTTOM_PADDING = 8.dp

/** The Settings hub's sub-pages (M8.6 spec §4.4, plan ruling R1). Routing and Per-app have their own routes. */
enum class SettingsPageId(
    @param:StringRes internal val titleRes: Int,
) {
    Appearance(R.string.settings_section_appearance),
    DeviceId(R.string.settings_section_device_id),
    Latency(R.string.settings_latency_section),
    Dns(R.string.settings_dns_title),
    Geo(R.string.settings_section_geo),
    About(R.string.settings_section_about),
}

/**
 * One Settings sub-page, pushed from the hub as `SettingsPage(page)` (M8.6 plan ruling R1).
 *
 * Until M8.6 Part 4 Task 4 restyles them, each page hosts the section composables the old single
 * Settings screen showed for that topic, under a plain top bar.
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

/** The stateless half of [SettingsPageScreen]. */
@Composable
internal fun SettingsPageContent(
    page: SettingsPageId,
    state: SettingsState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        PageTopBar(title = stringResource(page.titleRes), onBack = onBack)
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            when (page) {
                SettingsPageId.Appearance -> {
                    AppearanceControl(selected = state.theme, onThemeChanged = actions.onThemeChanged)
                    if (state.dynamicColorAvailable) DynamicColorRow(state.dynamicColor, actions.onDynamicColorChanged)
                }
                SettingsPageId.DeviceId ->
                    HwidControl(
                        enabled = state.hwidEnabled,
                        hwid = state.hwid,
                        onEnabledChanged = actions.onHwidEnabledChanged,
                    )
                SettingsPageId.Latency -> LatencyControls(state = state, actions = actions)
                SettingsPageId.Dns -> SettingsDnsSection(state = state, actions = actions)
                SettingsPageId.Geo -> SettingsGeoSection(state = state, actions = actions)
                SettingsPageId.About -> AboutRows(state)
            }
        }
    }
}

@Composable
private fun PageTopBar(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(SubspaceIcons.ArrowBack),
                contentDescription = stringResource(R.string.settings_back),
            )
        }
        Text(text = title, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * System/Light/Dark as a single-choice segmented control — Material3's own
 * selection widget for exactly three mutually-exclusive, always-visible
 * options. Task 4 replaces it with `ModeTiles`.
 */
@Composable
private fun AppearanceControl(
    selected: ThemePreference,
    onThemeChanged: (ThemePreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        ThemePreference.entries.forEachIndexed { index, option ->
            val isSelected = option == selected
            val description =
                stringResource(
                    if (isSelected) {
                        R.string.settings_theme_option_selected_description
                    } else {
                        R.string.settings_theme_option_description
                    },
                    stringResource(option.labelRes()),
                )
            SegmentedButton(
                selected = isSelected,
                onClick = { onThemeChanged(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemePreference.entries.size),
                modifier = Modifier.semantics { contentDescription = description },
                label = { Text(stringResource(option.labelRes())) },
            )
        }
    }
}

@Composable
private fun HwidControl(
    enabled: Boolean,
    hwid: String,
    onEnabledChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.settings_hwid_title)
    val icon = ImageVector.vectorResource(SubspaceIcons.Fingerprint)
    SettingRow(
        icon = icon,
        label = title,
        supportingText = stringResource(R.string.settings_hwid_summary),
        trailing = {
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChanged,
                modifier = Modifier.semantics { contentDescription = title },
            )
        },
        modifier = modifier,
    )
    // The HWID is 43 unbreakable monospace characters, so it cannot live in SettingRow's
    // `trailing` slot: that slot is measured at its intrinsic width before the label column's
    // weight(1f) is resolved, so the value claimed the whole row and squeezed the label to about
    // one character, rendering it as a vertical stack of letters. Found on device. It is a
    // full-width value, not a control — so it goes on its own line beneath the row.
    SettingRow(
        icon = icon,
        label = stringResource(R.string.settings_hwid_value_title),
        supportingText = stringResource(R.string.settings_hwid_value_summary),
    )
    SelectionContainer {
        Text(
            text = hwid,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = HWID_VALUE_START_PADDING, bottom = HWID_VALUE_BOTTOM_PADDING),
        )
    }
}

@Composable
private fun AboutRows(state: SettingsState) {
    val info = ImageVector.vectorResource(SubspaceIcons.Info)
    SettingRow(
        icon = info,
        label = stringResource(R.string.settings_about_app_version_label),
        supportingText = state.appVersion,
    )
    SettingRow(
        icon = info,
        label = stringResource(R.string.settings_about_xray_version_label),
        supportingText = state.xrayVersion.displayText(),
    )
    SettingRow(
        icon = info,
        label = stringResource(R.string.settings_about_license_label),
        supportingText = stringResource(R.string.settings_about_license_value),
    )
}

internal fun ThemePreference.labelRes(): Int =
    when (this) {
        ThemePreference.System -> R.string.settings_theme_system
        ThemePreference.Light -> R.string.settings_theme_light
        ThemePreference.Dark -> R.string.settings_theme_dark
    }

@Composable
internal fun XrayVersionState.displayText(): String =
    when (this) {
        XrayVersionState.Loading -> stringResource(R.string.settings_about_xray_version_loading)
        is XrayVersionState.Available -> version
        XrayVersionState.Unavailable -> stringResource(R.string.settings_about_xray_version_unavailable)
    }
