// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.SubspaceTheme

class StatTileTest {
    @get:Rule
    val rule = createComposeRule()

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Test
    fun anEnabledActionTileIsAButtonWithItsDescription() {
        var clicks = 0
        rule.setContent {
            SubspaceTheme(dynamicColor = false) {
                StatTile(
                    icon = SubspaceIcons.NetworkPing,
                    value = "42 ms",
                    label = "Latency",
                    onClick = { clicks++ },
                    actionDescription = "Tap to test",
                )
            }
        }
        rule.onNodeWithContentDescription("Tap to test").assert(isButton).assertIsEnabled().performClick()
        clicks shouldBe 1
    }

    /**
     * An action that cannot run now is a disabled button, not a live one: the
     * latency tile while a test is in flight or no server is chosen.
     */
    @Test
    fun aDisabledActionTileIsAnnouncedAsADisabledButton() {
        var clicks = 0
        rule.setContent {
            SubspaceTheme(dynamicColor = false) {
                StatTile(
                    icon = SubspaceIcons.NetworkPing,
                    value = "Testing…",
                    label = "Latency",
                    onClick = { clicks++ },
                    enabled = false,
                    actionDescription = "Tap to test",
                )
            }
        }
        rule.onNodeWithContentDescription("Tap to test").assert(isButton).assertIsNotEnabled().performClick()
        clicks shouldBe 0
    }

    /** A read-out has no action, so it carries no button role and no action hint. */
    @Test
    fun aReadOutTileHasNoActionAndNoHint() {
        rule.setContent {
            SubspaceTheme(dynamicColor = false) {
                StatTile(
                    icon = SubspaceIcons.SouthEast,
                    value = "12 MB",
                    label = "Down",
                    actionDescription = "Tap to test",
                )
            }
        }
        rule.onNodeWithContentDescription("Tap to test").assertDoesNotExist()
        rule
            .onNodeWithText("12 MB")
            .assert(!SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .assert(!isButton)
    }
}
