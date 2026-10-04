// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.list

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant

/** M8.6 Part 3 Task 5: the rename and delete-group dialogs in all four themes. */
class ServersDialogsSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private val group = ServersGroup(id = 7L, name = "Local configs", totalProfileCount = 3, profiles = emptyList())
    private val actions = ServersDialogActions({ _, _ -> }, {}, {}, {})

    private fun shoot(
        name: String,
        rename: ServersGroup? = null,
        delete: ServersGroup? = null,
    ) {
        ThemeVariant.entries.forEach { v ->
            paparazzi.snapshot(name = "$name-${v.fileSuffix}") {
                SubspacePreviewTheme(v) { ServersDialogs(rename, delete, actions) }
            }
        }
    }

    @Test
    fun renameGroup() = shoot("dialog-rename-group", rename = group)

    @Test
    fun deleteGroup() = shoot("dialog-delete-group", delete = group)
}
