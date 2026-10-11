// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.theme.tabular
import kotlin.math.roundToInt

/**
 * An integer-stepped slider with its label and current value on one line (M8.6 spec §3.1).
 * [onValueChange] fires once per gesture, when it ends, with the final value.
 */
@Suppress("LongParameterList")
@Composable
fun SliderRow(
    label: String,
    value: Int,
    range: IntRange,
    valueText: (Int) -> String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The drag lives in local state and is committed once it ends, so a slow write cannot drag
    // the thumb back mid-gesture. Keyed on the caller's value: a new external value wins. Declared
    // above the label so the number follows the drag too.
    var local by remember(value) { mutableIntStateOf(value) }
    var dirty by remember(value) { mutableStateOf(false) }
    Column(modifier.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText(local),
                style = MaterialTheme.typography.bodyLarge.tabular(),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val state =
            rememberSliderState(
                local.toFloat(),
                (range.last - range.first - 1).coerceAtLeast(0),
                range.first.toFloat()..range.last.toFloat(),
            )
        SideEffect { state.value = local.toFloat() }
        // TalkBack's set-progress action and keyboard arrows call onValueChange then this, so
        // they commit too. Touch commits on release.
        Slider(
            state = state,
            onValueChangeFinished = {
                if (dirty) onValueChange(local)
                dirty = false
            },
            onValueChange = {
                local = it.roundToInt().coerceIn(range)
                dirty = true
            },
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
