// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.component.SwitchRow
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.mono
import space.getsub.feature.settings.R
import space.getsub.feature.settings.SettingsActions
import space.getsub.feature.settings.SettingsState

/** Aligns the value with the switch row's label column: its 24.dp icon plus a 16.dp gap. */
private val VALUE_START_PADDING = 40.dp

/**
 * The Send HWID switch, and the value as selectable monospace text on its own full-width lines.
 *
 * The value is 43 unbreakable characters, so it must never go in a row's trailing slot: that slot
 * is measured at intrinsic width before the label's weight is resolved, which once squeezed the
 * label to one character per line (found on device in M4; SettingsHwidLayoutTest guards it).
 */
@Composable
internal fun DeviceIdPage(
    state: SettingsState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageScaffold(title = stringResource(R.string.settings_section_device_id), onBack = onBack, modifier = modifier) {
        SwitchRow(
            icon = SubspaceIcons.Fingerprint,
            label = stringResource(R.string.settings_hwid_title),
            supporting = stringResource(R.string.settings_hwid_summary),
            checked = state.hwidEnabled,
            onCheckedChange = actions.onHwidEnabledChanged,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = stringResource(R.string.settings_hwid_value_title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = VALUE_START_PADDING, top = 12.dp),
        )
        Text(
            text = stringResource(R.string.settings_hwid_value_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = VALUE_START_PADDING),
        )
        SelectionContainer {
            Text(
                text = state.hwid,
                style = MaterialTheme.typography.bodySmall.mono(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = VALUE_START_PADDING, top = 6.dp, bottom = 8.dp),
            )
        }
    }
}
