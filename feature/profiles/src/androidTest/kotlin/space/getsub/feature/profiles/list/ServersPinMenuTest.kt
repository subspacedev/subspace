// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.list

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.theme.SubspaceTheme

// camelCase throughout, same DEX-040 constraint ServersDialogsTest documents.
//
// M10-C spec §3.4: "Pin to top" / "Unpin" in each group's More options menu.
class ServersPinMenuTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        group: ServersGroup,
        onPin: (Long, Boolean) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            SubspaceTheme {
                ServersScreenContent(
                    state = ServersState(groups = listOf(group)),
                    actions =
                    ServersActions(
                        onQueryChanged = {},
                        onProtocolFilterChanged = {},
                        onSortChanged = {},
                        onProfileSelected = {},
                        onRenameGroup = { _, _ -> },
                        onDeleteGroup = {},
                        onGroupPinChanged = onPin,
                        onAddProfile = {},
                        onProfileEdit = {},
                        onUpdateSubscription = {},
                        onDismissUpdateResult = {},
                        onOpenSubscriptionDetail = {},
                        onAllowRedirects = {},
                        onDenyRedirects = {},
                        onTestProfile = {},
                        onTestGroup = {},
                        onCancelTests = {},
                        onGroupSortChanged = { _, _ -> },
                    ),
                )
            }
        }
    }

    private fun group(pinned: Boolean) =
        ServersGroup(id = 7L, name = "Local configs", totalProfileCount = 0, profiles = emptyList(), pinned = pinned)

    @Test
    fun anUnpinnedGroupOffersPinToTop() {
        setContent(group(pinned = false))
        composeRule.onNodeWithContentDescription("More options for Local configs").performClick()

        composeRule.onNodeWithText("Pin to top").assertExists()
        composeRule.onNodeWithText("Unpin").assertDoesNotExist()
    }

    @Test
    fun aPinnedGroupOffersUnpin() {
        setContent(group(pinned = true))
        composeRule.onNodeWithContentDescription("More options for Local configs").performClick()

        composeRule.onNodeWithText("Unpin").assertExists()
        composeRule.onNodeWithText("Pin to top").assertDoesNotExist()
    }

    @Test
    fun aPinnedGroupShowsAPinIndicator() {
        setContent(group(pinned = true))

        composeRule.onNodeWithContentDescription("Pinned", substring = true).assertExists()
    }

    @Test
    fun choosingPinToTopReportsThatGroupAsPinned() {
        var reported: Pair<Long, Boolean>? = null
        setContent(group(pinned = false), onPin = { id, pinned -> reported = id to pinned })
        composeRule.onNodeWithContentDescription("More options for Local configs").performClick()
        composeRule.onNodeWithText("Pin to top").performClick()

        reported shouldBe (7L to true)
    }

    @Test
    fun choosingUnpinReportsThatGroupAsUnpinned() {
        var reported: Pair<Long, Boolean>? = null
        setContent(group(pinned = true), onPin = { id, pinned -> reported = id to pinned })
        composeRule.onNodeWithContentDescription("More options for Local configs").performClick()
        composeRule.onNodeWithText("Unpin").performClick()

        reported shouldBe (7L to false)
    }
}
