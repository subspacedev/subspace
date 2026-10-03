// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.theme.SubspaceTheme

class OverlaysTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun undoReportsThatUndoWasPressed() {
        var undone: Boolean? = null
        rule.setContent {
            SubspaceTheme(dynamicColor = false) {
                val host = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                UndoSnackbarHost(host)
                LaunchedEffect(Unit) {
                    scope.launch { undone = host.showUndo("Switched to Amsterdam Core", "Undo") }
                }
            }
        }
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        undone shouldBe true
    }

    @Test
    fun searchFieldClearsWithTheCloseButton() {
        var query by mutableStateOf("ams")
        rule.setContent {
            SubspaceTheme(dynamicColor = false) {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "Search",
                )
            }
        }
        rule.onNodeWithContentDescription("Clear search").performClick()
        query shouldBe ""
    }

    @Test
    fun destructiveDialogConfirms() {
        var confirmed = false
        rule.setContent {
            SubspaceTheme(dynamicColor = false) {
                SubspaceDialog(
                    title = "Discard changes?",
                    body = "Your edits to this rule set will be lost.",
                    confirmLabel = "Discard",
                    onConfirm = { confirmed = true },
                    dismissLabel = "Keep editing",
                    onDismiss = {},
                    destructive = true,
                )
            }
        }
        rule.onNodeWithText("Discard").performClick()
        confirmed shouldBe true
    }
}
