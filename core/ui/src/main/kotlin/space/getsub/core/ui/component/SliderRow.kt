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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.theme.tabular
import kotlin.math.roundToInt

/** An integer-stepped slider with its label and current value on one line (M8.6 spec §3.1). */
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
    Column(modifier.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText(value),
                style = MaterialTheme.typography.bodyLarge.tabular(),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val state =
            rememberSliderState(
                value.toFloat(),
                (range.last - range.first - 1).coerceAtLeast(0),
                range.first.toFloat()..range.last.toFloat(),
            )
        // The caller's value is the source of truth; the state only tracks the drag.
        state.value = value.toFloat()
        Slider(
            state = state,
            onValueChange = { onValueChange(it.roundToInt().coerceIn(range)) },
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
