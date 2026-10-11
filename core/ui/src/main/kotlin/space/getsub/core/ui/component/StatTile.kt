// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.getsub.core.ui.theme.SubspaceShapeTokens
import space.getsub.core.ui.theme.tabular

/**
 * One metric — an icon, a value and a label — on a tonal tile (M8.6 spec §4.1.1).
 *
 * [value] is formatted by the caller, who branches on the measurement's outcome
 * first: an unmeasured latency arrives here as an em-dash, never a zero, because
 * a number that looks measured but is invented is the failure ARCHITECTURE.md
 * §10.1 warns about. Passing [onClick] makes the whole tile a button (the
 * latency tile re-tests on tap); null leaves it a plain read-out.
 *
 * A tile whose action exists but cannot run right now (a test already in
 * flight, no server chosen) keeps [onClick] and sets [enabled] false, so
 * TalkBack announces a disabled button rather than a read-out or a live
 * action that does nothing. [actionDescription] is what TalkBack reads for a
 * button tile; it is ignored on a read-out, which has no action to describe.
 */
@Suppress("LongParameterList")
@Composable
fun StatTile(
    @DrawableRes icon: Int,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    shape: Shape = SubspaceShapeTokens.cardLead,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    actionDescription: String? = null,
) {
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp)) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            val valueStyle = MaterialTheme.typography.titleLarge.tabular()
            Text(
                text = value,
                style = valueStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
                // Three tiles share a 360 dp row, leaving ~88 dp: "302.7 KB" wraps at
                // titleLarge. Shrink to fit on one line instead of splitting a number.
                // The floor is low enough that the worst case ("1023.9 GB" at 2x font
                // scale) still fits whole: a value is never clipped or ellipsized, because
                // "1.2 GB" cut to "1.2" would read as a different number.
                maxLines = 1,
                // No soft wrap: otherwise "GB" wraps to a hidden second line and autoSize
                // settles on a size where only the number shows.
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = valueStyle.fontSize),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (onClick != null) {
        val button =
            modifier.semantics {
                role = Role.Button
                if (!enabled) disabled()
                if (actionDescription != null) contentDescription = actionDescription
            }
        Surface(onClick = onClick, enabled = enabled, shape = shape, color = color, modifier = button) {
            body()
        }
    } else {
        Surface(shape = shape, color = color, modifier = modifier, content = body)
    }
}
