// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.R
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.SubspaceTheme

// camelCase test names: this module's minSdk (26) makes D8 reject spaces in
// synthetic class names derived from test method names (see GroupCardTest.kt).
class FloatingNavBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onlyTheSelectedDestinationShowsItsLabel() {
        composeRule.setContent {
            SubspaceTheme { FloatingNavBar(items = threeItems(), selected = "servers", onSelect = {}) }
        }
        composeRule.onNodeWithText("Servers").assertExists()
        composeRule.onNodeWithText("Connect").assertDoesNotExist()
    }

    // Labels are hidden for unselected items, so the content description is
    // the only accessible name they have.
    @Test
    fun everyDestinationIsReachableByContentDescription() {
        composeRule.setContent {
            SubspaceTheme { FloatingNavBar(items = threeItems(), selected = "servers", onSelect = {}) }
        }
        composeRule.onNodeWithContentDescription("Connect").assertExists()
        composeRule.onNodeWithContentDescription("Settings").assertExists()
    }

    @Test
    fun tappingAnUnselectedDestinationReportsItsValue() {
        var selectedValue: String? = null
        composeRule.setContent {
            SubspaceTheme {
                FloatingNavBar(items = threeItems(), selected = "servers", onSelect = { selectedValue = it })
            }
        }
        composeRule.onNodeWithContentDescription("Settings").performClick()
        selectedValue shouldBe "settings"
    }

    // The component must not suppress re-taps: callers use them for
    // "tap current tab to scroll to top".
    @Test
    fun tappingTheSelectedDestinationStillReportsItsValue() {
        var selectedValue: String? = null
        composeRule.setContent {
            SubspaceTheme {
                FloatingNavBar(items = threeItems(), selected = "servers", onSelect = { selectedValue = it })
            }
        }
        composeRule.onNodeWithText("Servers").performClick()
        selectedValue shouldBe "servers"
    }

    private fun threeItems(): List<NavPillItem> =
        listOf(
            NavPillItem("connect", R.string.nav_item_connect, SubspaceIcons.Home, SubspaceIcons.HomeFilled),
            NavPillItem("servers", R.string.nav_item_servers, SubspaceIcons.Dns, SubspaceIcons.DnsFilled),
            NavPillItem("settings", R.string.nav_item_settings, SubspaceIcons.Settings, SubspaceIcons.SettingsFilled),
        )
}
