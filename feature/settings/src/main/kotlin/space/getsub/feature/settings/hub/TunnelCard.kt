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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 *
 * Its rows are drawn here rather than with the kit's `SwitchRow`, for fix A6: every subtitle fits
 * on one line at 360dp, which needs the design's smaller supporting text and tighter gaps than the
 * kit's general-purpose row.
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
        Column(Modifier.padding(horizontal = CARD_PADDING, vertical = 6.dp)) {
            TunnelLinkRow(
                ALWAYS_ON,
                colors,
                actions.onOpenVpnSettings,
            )
            TunnelSwitchRow(
                BOOT_AUTOSTART,
                state.bootAutostart,
                actions.onBootAutostartChange,
                colors,
            )
            TunnelSwitchRow(
                KILL_SWITCH.copy(note = KILL_SWITCH_NOTE.takeIf { state.killSwitchExtraLine() }),
                state.failClosed,
                actions.onFailClosedChange,
                colors,
            )
            TunnelLinkRow(
                BATTERY,
                colors,
                actions.onOpenBatterySettings,
            )
            TunnelSwitchRow(
                LIGHTWEIGHT.copy(note = LIGHTWEIGHT_NOTE.takeIf { state.lightweightExtraLine() }),
                state.lightweightMode,
                actions.onLightweightModeChange,
                colors,
            )
        }
    }
}

private val CARD_PADDING = 12.dp

private val ALWAYS_ON =
    TunnelRowText(SubspaceIcons.VpnLock, R.string.settings_always_on_title, R.string.settings_always_on_summary)
private val BOOT_AUTOSTART =
    TunnelRowText(
        SubspaceIcons.RestartAlt,
        R.string.settings_boot_autostart_title,
        R.string.settings_boot_autostart_summary,
    )
private val KILL_SWITCH =
    TunnelRowText(SubspaceIcons.Block, R.string.settings_fail_closed_title, R.string.settings_fail_closed_summary)
private val BATTERY =
    TunnelRowText(SubspaceIcons.BatterySaver, R.string.settings_battery_title, R.string.settings_battery_summary)
private val KILL_SWITCH_NOTE = R.string.settings_fail_closed_per_app_note
private val LIGHTWEIGHT_NOTE = R.string.settings_lightweight_connected_note
private val LIGHTWEIGHT =
    TunnelRowText(SubspaceIcons.Eco, R.string.settings_lightweight_title, R.string.settings_lightweight_summary)
private val ROW_GAP = 12.dp

/** A Tunnel row's icon and copy; [note] is the conditional second line, or null. */
private data class TunnelRowText(
    @param:DrawableRes val icon: Int,
    @param:StringRes val label: Int,
    @param:StringRes val supporting: Int,
    @param:StringRes val note: Int? = null,
)

/**
 * A switch row. The whole row toggles, as the kit's `SwitchRow` does, so it is one toggleable node
 * carrying its own label.
 */
@Composable
private fun TunnelSwitchRow(
    text: TunnelRowText,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    colors: SwitchRowColors,
) {
    TunnelRow(
        text = text,
        colors = colors,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Switch(checked = checked, onCheckedChange = null, colors = colors.switch)
    }
}

/**
 * A row that opens a system settings screen. The whole row is one button, so
 * `SettingsTunnelSectionTest` can assert that nothing toggleable sits under its label.
 */
@Composable
private fun TunnelLinkRow(
    text: TunnelRowText,
    colors: SwitchRowColors,
    onClick: () -> Unit,
) {
    TunnelRow(text = text, colors = colors, modifier = Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Icon(
            painterResource(SubspaceIcons.OpenInNew),
            contentDescription = null,
            tint = colors.content,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun TunnelRow(
    text: TunnelRowText,
    colors: SwitchRowColors,
    modifier: Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).then(modifier).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        Icon(
            painterResource(text.icon),
            contentDescription = null,
            tint = colors.content,
            // The design's 20px glyph.
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(text.label), style = MaterialTheme.typography.bodyLarge, color = colors.content)
            // bodySmall without its 0.4sp tracking, close to the design's plain 13px: fix A6 needs
            // each subtitle on one line at 360dp, and the tracking alone costs a word there.
            val small = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.sp)
            Text(stringResource(text.supporting), style = small, color = colors.supporting)
            text.note?.let { Text(stringResource(it), style = small, color = colors.content) }
        }
        trailing()
    }
}
