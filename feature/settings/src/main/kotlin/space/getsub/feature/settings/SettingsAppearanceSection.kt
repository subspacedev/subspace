// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import space.getsub.core.ui.component.SwitchRow
import space.getsub.core.ui.icon.SubspaceIcons

/** The Dynamic colour switch (M8.6 spec §4.4). The caller hides it below Android 12. */
@Composable
internal fun DynamicColorRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchRow(
        icon = SubspaceIcons.Palette,
        label = stringResource(R.string.settings_dynamic_color_title),
        supporting = stringResource(R.string.settings_dynamic_color_summary),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
