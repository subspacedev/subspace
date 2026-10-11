// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// RadioChoice is RadioList's own parameter type, kept beside the one composable that takes it.
@file:Suppress("MatchingDeclarationName")

package space.getsub.feature.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** One choice of a [RadioList]. */
internal data class RadioChoice(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

/**
 * A vertical single-choice list. Used where the labels do not fit on one line in side-by-side tiles
 * at 360 dp ("runetfreedom (RU)" wrapped in the old segmented control, and tiles are narrower still).
 */
@Composable
internal fun RadioList(
    choices: List<RadioChoice>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.selectableGroup()) {
        choices.forEach { choice ->
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = choice.selected, role = Role.RadioButton, onClick = choice.onSelect),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = choice.selected, onClick = null)
                Text(
                    text = choice.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}
