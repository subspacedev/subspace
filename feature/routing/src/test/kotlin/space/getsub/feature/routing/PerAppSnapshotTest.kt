// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.model.PerAppMode
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant

/**
 * M8.6 Part 4 Task 7: the per-app proxy screen (M8.6 spec §4.7) in all four themes — an
 * allow-list with picks and a deny-list with picks, one of them no longer installed. Paparazzi
 * has no installed apps, so every icon is the initial-letter tile.
 */
class PerAppSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 2400))

    private val noActions =
        PerAppActions(
            onSetMode = {},
            onToggle = {},
            onSearch = {},
            onClear = {},
            onSave = {},
            onDiscard = {},
            onBack = {},
        )

    private fun apps(selected: Set<String>): List<AppRow> =
        listOf(
            AppRow("org.example.browser", "Browser", "org.example.browser" in selected),
            AppRow("org.example.bank", "Bank", "org.example.bank" in selected),
            AppRow("org.example.maps", "Maps", "org.example.maps" in selected),
            AppRow("org.example.music", "Music", "org.example.music" in selected),
            AppRow("org.example.notes", "Notes", "org.example.notes" in selected),
            AppRow("org.example.old", "org.example.old", isSelected = true, isInstalled = false),
        )

    private fun state(
        mode: PerAppMode,
        selected: Set<String>,
        isTunnelActive: Boolean = false,
    ): PerAppState {
        val rows = apps(selected + "org.example.old")
        return PerAppState(
            mode = mode,
            rows = rows,
            picked = rows.filter { it.isSelected },
            selectedCount = rows.count { it.isSelected },
            isDirty = true,
            isTunnelActive = isTunnelActive,
        )
    }

    private fun shoot(
        name: String,
        state: PerAppState,
    ) {
        ThemeVariant.entries.forEach { v ->
            paparazzi.snapshot(name = "$name-${v.fileSuffix}") {
                SubspacePreviewTheme(v) { PerAppScreenContent(state = state, actions = noActions) }
            }
        }
    }

    @Test
    fun allowList() =
        shoot(
            "per-app-allow",
            state(PerAppMode.AllowList, setOf("org.example.bank", "org.example.maps"), isTunnelActive = true),
        )

    @Test
    fun denyList() = shoot("per-app-deny", state(PerAppMode.DenyList, setOf("org.example.bank")))
}
