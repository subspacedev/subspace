// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.espresso.Espresso
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.theme.SubspaceTheme

// camelCase throughout: same DEX-040 constraint as the other core/ui tests. Ports the two
// sheet-lock tests that went with the retired SubspaceBottomSheetTest, with the same meaning.
class SubspaceSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val contentTag = "sheet-content"

    // System back is the dismissal a test can address directly (scrim and swipe are the dialog
    // window's own plumbing), and the one TalkBack and the back gesture always reach.
    @Test
    fun dismissCallbackFiresOnSystemBackPress() {
        var dismissed = false
        composeRule.setContent {
            SubspaceTheme {
                SubspaceSheet(onDismiss = { dismissed = true }, dismissible = true) {
                    Box(modifier = Modifier.testTag(contentTag))
                }
            }
        }
        composeRule.waitForIdle()
        Espresso.pressBack()
        composeRule.waitForIdle()
        dismissed shouldBe true
    }

    @Test
    fun aNonDismissibleSheetDoesNotCloseOnSystemBackPress() {
        var dismissed = false
        composeRule.setContent {
            SubspaceTheme {
                SubspaceSheet(onDismiss = { dismissed = true }, dismissible = false) {
                    Box(modifier = Modifier.testTag(contentTag))
                }
            }
        }
        composeRule.waitForIdle()
        Espresso.pressBack()
        composeRule.waitForIdle()
        dismissed shouldBe false
        composeRule.onNodeWithTag(contentTag).assertExists()
    }
}
