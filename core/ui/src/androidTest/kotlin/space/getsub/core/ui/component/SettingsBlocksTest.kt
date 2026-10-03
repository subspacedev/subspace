// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.SubspaceTheme

class SettingsBlocksTest {
    @get:Rule
    val rule = createComposeRule()

    /** The whole row is the target, not just the 52 dp switch (M8.6 spec §4.9 A7 spirit). */
    @Test
    fun tappingTheLabelTogglesTheSwitchRow() {
        var checked by mutableStateOf(false)
        rule.setContent {
            SubspaceTheme(dynamicColor = false) {
                SwitchRow(
                    icon = SubspaceIcons.Block,
                    label = "Kill switch",
                    supporting = null,
                    checked = checked,
                    onCheckedChange = { checked = it },
                )
            }
        }
        rule.onNodeWithText("Kill switch").performClick()
        checked shouldBe true
    }

    @Test
    fun modeTilesSelectExactlyOne() {
        var selected by mutableIntStateOf(0)
        rule.setContent {
            SubspaceTheme(dynamicColor = false) {
                ModeTiles(
                    options = listOf(
                        ModeTile(SubspaceIcons.SelectAll, "Off"),
                        ModeTile(SubspaceIcons.PlaylistAddCheck, "Allow-list"),
                        ModeTile(SubspaceIcons.Block, "Deny-list"),
                    ),
                    selected = selected,
                    onSelect = { selected = it },
                )
            }
        }
        rule.onNodeWithText("Deny-list").performClick()
        selected shouldBe 2
        rule.onNodeWithText("Deny-list").assertIsSelected()
        rule.onNodeWithText("Off").assertIsNotSelected()
        rule.onNodeWithText("Allow-list").assertIsNotSelected()
    }

    @Test
    fun hubRowIsOneClickTarget() {
        var clicks = 0
        rule.setContent {
            SubspaceTheme(dynamicColor = false) {
                HubRow(
                    icon = SubspaceIcons.AltRoute,
                    label = "Routing",
                    value = "Smart route active",
                    onClick = { clicks++ },
                )
            }
        }
        rule.onNodeWithText("Smart route active").performClick()
        clicks shouldBe 1
    }
}
