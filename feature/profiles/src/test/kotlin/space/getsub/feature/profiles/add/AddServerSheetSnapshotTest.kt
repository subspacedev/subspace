// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.add

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant

/** M8.6 Part 3 Task 5: the add-server sheet's content, idle and busy, in all four themes. */
class AddServerSheetSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 2400))

    private val noActions = ImportActions({}, {}, {}, {}, {}, {})

    private fun shoot(
        name: String,
        state: ImportState,
    ) {
        ThemeVariant.entries.forEach { v ->
            paparazzi.snapshot(name = "$name-${v.fileSuffix}") {
                SubspacePreviewTheme(v) {
                    AddServerSheetContent(
                        state = state,
                        actions = noActions,
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    )
                }
            }
        }
    }

    @Test
    fun idle() = shoot("add-server-idle", ImportState())

    @Test
    fun subscriptionUrlShowsTheHwidField() =
        shoot(
            "add-server-subscription-hwid",
            ImportState(input = "https://example.com/sub", subscriptionHwid = "aB3dE5fG7hJ9kL1mN3pQ5rS7tU9vW1xY"),
        )

    @Test
    fun busy() = shoot("add-server-busy", ImportState(input = "vless://x@198.51.100.7:443", busy = true))
}
