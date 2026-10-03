// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.R
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.snapshot.snapshotThemes

class FloatingNavBarSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi()

    private val items =
        listOf(
            NavPillItem("connect", R.string.nav_item_connect, SubspaceIcons.Home, SubspaceIcons.HomeFilled),
            NavPillItem("servers", R.string.nav_item_servers, SubspaceIcons.Dns, SubspaceIcons.DnsFilled),
            NavPillItem("settings", R.string.nav_item_settings, SubspaceIcons.Settings, SubspaceIcons.SettingsFilled),
        )

    @Test
    fun connectSelected() =
        paparazzi.snapshotThemes("nav-bar-connect") { FloatingNavBar(items, "connect", onSelect = {}) }

    @Test
    fun serversSelected() =
        paparazzi.snapshotThemes("nav-bar-servers") { FloatingNavBar(items, "servers", onSelect = {}) }

    @Test
    fun settingsSelected() =
        paparazzi.snapshotThemes("nav-bar-settings") { FloatingNavBar(items, "settings", onSelect = {}) }
}
