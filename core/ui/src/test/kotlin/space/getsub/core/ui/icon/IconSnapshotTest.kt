// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.icon

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.snapshot.snapshotThemes

class IconSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun sheet() =
        paparazzi.snapshotThemes("icons") {
            FlowRow {
                SubspaceIcons.all.forEach { (name, id) ->
                    Icon(painterResource(id), contentDescription = name, modifier = Modifier.padding(6.dp))
                }
            }
        }
}
