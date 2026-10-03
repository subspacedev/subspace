// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Colours for a [SwitchRow]; the Tunnel card passes its own (M8.6 spec §5.1, fix A12). */
data class SwitchRowColors(
    val content: Color,
    val supporting: Color,
    val switch: SwitchColors,
)

object SwitchRowDefaults {
    private const val SUPPORTING_ALPHA = 0.8f

    @Composable
    fun colors(): SwitchRowColors =
        SwitchRowColors(
            content = MaterialTheme.colorScheme.onSurface,
            supporting = MaterialTheme.colorScheme.onSurfaceVariant,
            switch = SwitchDefaults.colors(),
        )

    /** For a switch drawn on a tertiaryContainer card. */
    @Composable
    fun onTertiaryContainer(): SwitchRowColors {
        val s = MaterialTheme.colorScheme
        return SwitchRowColors(
            content = s.onTertiaryContainer,
            supporting = s.onTertiaryContainer.copy(alpha = SUPPORTING_ALPHA),
            switch =
            SwitchDefaults.colors(
                checkedTrackColor = s.tertiary,
                checkedThumbColor = s.onTertiary,
                uncheckedBorderColor = s.onTertiaryContainer,
                uncheckedThumbColor = s.onTertiaryContainer,
                uncheckedTrackColor = Color.Transparent,
            ),
        )
    }
}

/**
 * A setting with a switch. The whole row toggles, so the label is as good a
 * target as the switch. [note] is an optional second line shown only when it
 * applies (M8.6 spec §5.1: the kill switch's per-app caveat, lightweight
 * mode's "from your next connection").
 */
@Suppress("LongParameterList")
@Composable
fun SwitchRow(
    @DrawableRes icon: Int?,
    label: String,
    supporting: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    colors: SwitchRowColors = SwitchRowDefaults.colors(),
) {
    Row(
        modifier =
        modifier
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = colors.content,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.content)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.supporting,
                )
            }
            if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium, color = colors.content)
        }
        Switch(checked = checked, onCheckedChange = null, colors = colors.switch)
    }
}
