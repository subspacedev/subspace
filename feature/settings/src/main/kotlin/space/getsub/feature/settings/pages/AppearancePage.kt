// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.getsub.core.data.ThemePreference
import space.getsub.core.ui.component.ModeTile
import space.getsub.core.ui.component.ModeTiles
import space.getsub.core.ui.component.SwitchRow
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.feature.settings.R
import space.getsub.feature.settings.SettingsActions
import space.getsub.feature.settings.SettingsState
import space.getsub.feature.settings.labelRes

/** System / Light / Dark tiles and the Dynamic colour switch, hidden below Android 12 (M8.6 spec §4.4). */
@Composable
internal fun AppearancePage(
    state: SettingsState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageScaffold(title = stringResource(R.string.settings_section_appearance), onBack = onBack, modifier = modifier) {
        val options =
            ThemePreference.entries.map { option ->
                ModeTile(icon = option.iconRes(), label = stringResource(option.labelRes()))
            }
        ModeTiles(
            options = options,
            selected = ThemePreference.entries.indexOf(state.theme),
            onSelect = { actions.onThemeChanged(ThemePreference.entries[it]) },
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = stringResource(R.string.settings_theme_system_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
        )
        if (state.dynamicColorAvailable) {
            SwitchRow(
                icon = SubspaceIcons.Palette,
                label = stringResource(R.string.settings_dynamic_color_title),
                supporting = stringResource(R.string.settings_dynamic_color_summary),
                checked = state.dynamicColor,
                onCheckedChange = actions.onDynamicColorChanged,
            )
        }
    }
}

private fun ThemePreference.iconRes(): Int =
    when (this) {
        ThemePreference.System -> SubspaceIcons.Contrast
        ThemePreference.Light -> SubspaceIcons.LightMode
        ThemePreference.Dark -> SubspaceIcons.DarkMode
    }
