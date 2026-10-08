// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.hub

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.component.SwitchRow
import space.getsub.core.ui.component.SwitchRowColors
import space.getsub.core.ui.component.SwitchRowDefaults
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.SubspaceShapeTokens
import space.getsub.feature.settings.R
import space.getsub.feature.settings.SettingsState

/**
 * The settings that keep the tunnel running under the platform, inline at the bottom of the hub
 * (M8.6 spec §5.1, fix A10). A `tertiaryContainer` card whose switches use that container's own
 * roles (fix A12). The kill switch and lightweight mode each gain a second line only while it
 * applies ([killSwitchExtraLine], [lightweightExtraLine]).
 */
@Composable
internal fun TunnelCard(
    state: SettingsState,
    actions: TunnelCardActions,
    modifier: Modifier = Modifier,
) {
    val colors = SwitchRowDefaults.onTertiaryContainer()
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = SubspaceShapeTokens.cardLead,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
            TunnelLinkRow(
                icon = SubspaceIcons.VpnLock,
                label = stringResource(R.string.settings_always_on_title),
                supporting = stringResource(R.string.settings_always_on_summary),
                colors = colors,
                onClick = actions.onOpenVpnSettings,
            )
            SwitchRow(
                icon = SubspaceIcons.RestartAlt,
                label = stringResource(R.string.settings_boot_autostart_title),
                supporting = stringResource(R.string.settings_boot_autostart_summary),
                checked = state.bootAutostart,
                onCheckedChange = actions.onBootAutostartChange,
                colors = colors,
            )
            SwitchRow(
                icon = SubspaceIcons.Block,
                label = stringResource(R.string.settings_fail_closed_title),
                supporting = stringResource(R.string.settings_fail_closed_summary),
                checked = state.failClosed,
                onCheckedChange = actions.onFailClosedChange,
                note = noteIf(state.killSwitchExtraLine(), R.string.settings_fail_closed_per_app_note),
                colors = colors,
            )
            TunnelLinkRow(
                icon = SubspaceIcons.BatterySaver,
                label = stringResource(R.string.settings_battery_title),
                supporting = stringResource(R.string.settings_battery_summary),
                colors = colors,
                onClick = actions.onOpenBatterySettings,
            )
            SwitchRow(
                icon = SubspaceIcons.Eco,
                label = stringResource(R.string.settings_lightweight_title),
                supporting = stringResource(R.string.settings_lightweight_summary),
                checked = state.lightweightMode,
                onCheckedChange = actions.onLightweightModeChange,
                note = noteIf(state.lightweightExtraLine(), R.string.settings_lightweight_connected_note),
                colors = colors,
            )
        }
    }
}

/**
 * A row that opens a system settings screen. The whole row is one button, so
 * `SettingsTunnelSectionTest` can assert that nothing toggleable sits under its label.
 */
@Composable
private fun TunnelLinkRow(
    @DrawableRes icon: Int,
    label: String,
    supporting: String,
    colors: SwitchRowColors,
    onClick: () -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = colors.content, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.content)
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = colors.supporting)
        }
        Icon(
            painterResource(SubspaceIcons.OpenInNew),
            contentDescription = null,
            tint = colors.content,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun noteIf(
    show: Boolean,
    @StringRes res: Int,
): String? = if (show) stringResource(res) else null
