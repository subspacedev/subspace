// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.getsub.core.model.PerAppMode

/**
 * Layout and rendering facts a JVM state test cannot see — same reasoning as
 * [RoutingListScreenContentTest], including its note that no device is reachable
 * in the authoring environment.
 *
 * Animations must be off and the screen unlocked (§11) or these fail
 * non-deterministically and look like product bugs.
 */
class PerAppScreenContentTest {
    @get:Rule
    val rule = createComposeRule()

    private val noopActions =
        PerAppActions(
            onSetMode = {},
            onToggle = {},
            onSearch = {},
            onClear = {},
            onSave = {},
            onDiscard = {},
            onBack = {},
        )

    private val rows =
        listOf(
            AppRow("com.example.bank", "Bank", isSelected = true),
            AppRow("com.example.maps", "Maps", isSelected = false),
        )

    @Test
    fun everyRowRendersItsLabel() {
        rule.setContent {
            PerAppScreenContent(PerAppState(mode = PerAppMode.DenyList, rows = rows), noopActions)
        }

        rule.onNodeWithText("Bank").assertIsDisplayed()
        rule.onNodeWithText("Maps").assertIsDisplayed()
    }

    // M5.5 spec §7.2: shown and flagged, never silently dropped.
    @Test
    fun anUninstalledSelectionIsMarkedRatherThanHidden() {
        val ghost = AppRow("com.example.deleted", "com.example.deleted", isSelected = true, isInstalled = false)
        rule.setContent {
            PerAppScreenContent(PerAppState(mode = PerAppMode.DenyList, rows = rows + ghost), noopActions)
        }

        rule.onNodeWithText("com.example.deleted").assertIsDisplayed()
        rule.onNodeWithText("Not installed").assertIsDisplayed()
    }

    // The warning is the UI half of M5.5 spec §6.3's guard. Save must be unavailable, not
    // merely discouraged.
    @Test
    fun anEmptyAllowListWarnsAndBlocksSave() {
        val unselected = rows.map { it.copy(isSelected = false) }
        rule.setContent {
            PerAppScreenContent(
                PerAppState(mode = PerAppMode.AllowList, rows = unselected, isDirty = true),
                noopActions,
            )
        }

        val emptyAllowListMessage =
            "Select at least one app, or switch the mode off. " +
                "A tunnel no app can use looks connected but carries nothing."
        rule.onNodeWithText(emptyAllowListMessage).assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    // M5.5 spec §7.3: the user is told before committing, not after the tunnel drops.
    // isTunnelActive covers Connecting as well as Connected — the notice has to
    // reach the cold-start window, which is where a save is most likely to
    // surprise someone.
    @Test
    fun anActiveTunnelIsAnnouncedBeforeSaving() {
        rule.setContent {
            PerAppScreenContent(
                PerAppState(mode = PerAppMode.DenyList, rows = rows, isDirty = true, isTunnelActive = true),
                noopActions,
            )
        }

        rule.onNodeWithText("Saving reconnects the tunnel").assertIsDisplayed()
    }

    // The count is the whole selection, not the visible slice of it: a query that
    // hides the ticked rows must not read as "0 apps selected".
    @Test
    fun theCountReportsTheSelectionRatherThanTheFilteredRows() {
        rule.setContent {
            PerAppScreenContent(
                PerAppState(
                    mode = PerAppMode.DenyList,
                    rows = emptyList(),
                    selectedCount = 2,
                    query = "zzzz",
                ),
                noopActions,
            )
        }

        rule.onNodeWithText("2 apps selected").assertIsDisplayed()
    }

    @Test
    fun aCleanDraftCannotBeSaved() {
        rule.setContent {
            PerAppScreenContent(PerAppState(mode = PerAppMode.DenyList, rows = rows, isDirty = false), noopActions)
        }

        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun offModeStillRendersTheModeChoices() {
        rule.setContent { PerAppScreenContent(PerAppState(rows = rows), noopActions) }

        rule.onNodeWithText("Off").assertIsDisplayed()
        rule.onNodeWithText("Allow-list").assertIsDisplayed()
        rule.onNodeWithText("Deny-list").assertIsDisplayed()
    }

    // Fix A8: the tag follows the mode and the selection, row by row.
    @Test
    fun allowListTagsSelectedTunnelledAndOthersDirect() {
        rule.setContent {
            PerAppScreenContent(PerAppState(mode = PerAppMode.AllowList, rows = rows, selectedCount = 1), noopActions)
        }

        rule.onAllNodesWithText("Tunnelled").assertCountEquals(1)
        rule.onAllNodesWithText("Direct").assertCountEquals(1)
        rule.onNode(hasText("Bank") and hasText("Tunnelled")).assertIsDisplayed()
    }

    @Test
    fun denyListTagsSelectedDirectAndOthersTunnelled() {
        rule.setContent {
            PerAppScreenContent(PerAppState(mode = PerAppMode.DenyList, rows = rows, selectedCount = 1), noopActions)
        }

        rule.onNode(hasText("Bank") and hasText("Direct")).assertIsDisplayed()
        rule.onNode(hasText("Maps") and hasText("Tunnelled")).assertIsDisplayed()
    }

    // The design hides the list in Off: no rows, no tags, no count; the selection is only noted.
    @Test
    fun offShowsNoAppRowsOrTags() {
        rule.setContent { PerAppScreenContent(PerAppState(rows = rows, selectedCount = 1), noopActions) }

        rule.onAllNodesWithText("Bank").assertCountEquals(0)
        rule.onAllNodesWithText("Maps").assertCountEquals(0)
        rule.onAllNodesWithText("Tunnelled").assertCountEquals(0)
        rule.onAllNodesWithText("Direct").assertCountEquals(0)
        rule.onAllNodesWithText("1 app selected").assertCountEquals(0)
    }

    @Test
    fun clearEmptiesThePickedTray() {
        var cleared = false
        rule.setContent {
            PerAppScreenContent(
                PerAppState(mode = PerAppMode.DenyList, rows = rows, picked = rows.take(1), selectedCount = 1),
                noopActions.copy(onClear = { cleared = true }),
            )
        }

        rule.onNodeWithText("Skipping the tunnel · 1").assertIsDisplayed()
        rule.onNodeWithText("Clear").performClick()
        assertTrue(cleared)
    }

    // A picked package that is no longer installed is chipped under its package name.
    @Test
    fun aGhostChipSitsInTheTrayAndClearRemovesIt() {
        val ghost = AppRow("com.example.deleted", "com.example.deleted", isSelected = true, isInstalled = false)
        var picked by mutableStateOf(listOf(ghost))
        rule.setContent {
            PerAppScreenContent(
                PerAppState(mode = PerAppMode.DenyList, picked = picked, selectedCount = picked.size),
                noopActions.copy(onClear = { picked = emptyList() }),
            )
        }

        rule.onNodeWithText("com.example.deleted").assertIsDisplayed()
        rule.onNodeWithText("Clear").performClick()
        rule.onAllNodesWithText("com.example.deleted").assertCountEquals(0)
    }

    @Test
    fun noPicksMeansNoTray() {
        rule.setContent { PerAppScreenContent(PerAppState(mode = PerAppMode.DenyList, rows = rows), noopActions) }

        rule.onAllNodesWithText("Clear").assertCountEquals(0)
    }
}
