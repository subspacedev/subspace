// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.theme.SubspaceTheme

/**
 * The rule set editor's discard guard (M8.6 spec §4.9, fix A11; M8.6 Part 4 plan review focus
 * 2): with unsaved edits, system Back and the top-bar arrow both ask before leaving; with none,
 * both leave without asking.
 *
 * Drives [DiscardGuard] around the real [RuleSetEditorContent] — [RuleSetEditorScreen] itself
 * needs a Hilt ViewModel — and presses Back through the activity's real
 * [OnBackPressedDispatcher]. An outer [BackHandler], registered first and so consulted last,
 * records a Back the guard let through: that is what the navigation host would have popped.
 *
 * camelCase test names, same DEX-040 constraint every other instrumented test in this repo
 * documents. Not run here (owner order 2026-10-08: no device work) — compiled only.
 */
class RuleSetEditorDiscardGuardTest {
    @get:Rule
    val rule = createComposeRule()

    private var left = 0
    private var fellThrough = 0
    private lateinit var dispatcher: OnBackPressedDispatcher

    private fun setContent(isDirty: Boolean) {
        rule.setContent {
            dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            BackHandler { fellThrough++ }
            SubspaceTheme {
                DiscardGuard(isDirty = isDirty, body = BODY, onLeave = { left++ }) { requestLeave ->
                    RuleSetEditorContent(
                        state = RuleSetEditorState(loading = false, name = "ads"),
                        actions = noOpActions.copy(onBack = requestLeave),
                    )
                }
            }
        }
    }

    private fun pressBack() = rule.runOnIdle { dispatcher.onBackPressed() }

    @Test
    fun backWithUnsavedEditsAsksFirst() {
        setContent(isDirty = true)

        pressBack()

        rule.onNodeWithText("Discard changes?").assertExists()
        fellThrough shouldBe 0
        left shouldBe 0
    }

    @Test
    fun backWithNoEditsLeavesWithoutAsking() {
        setContent(isDirty = false)

        pressBack()

        rule.onNodeWithText("Discard changes?").assertDoesNotExist()
        fellThrough shouldBe 1
    }

    @Test
    fun theArrowWithUnsavedEditsAsksFirst() {
        setContent(isDirty = true)

        rule.onNodeWithContentDescription("Back").performClick()

        rule.onNodeWithText("Discard changes?").assertExists()
        left shouldBe 0
    }

    @Test
    fun theArrowWithNoEditsLeavesWithoutAsking() {
        setContent(isDirty = false)

        rule.onNodeWithContentDescription("Back").performClick()

        rule.onNodeWithText("Discard changes?").assertDoesNotExist()
        left shouldBe 1
    }

    @Test
    fun discardLeaves() {
        setContent(isDirty = true)
        pressBack()

        rule.onNodeWithText("Discard").performClick()

        left shouldBe 1
        rule.onNodeWithText("Discard changes?").assertDoesNotExist()
    }

    @Test
    fun keepEditingStays() {
        setContent(isDirty = true)
        pressBack()

        rule.onNodeWithText("Keep editing").performClick()

        left shouldBe 0
        fellThrough shouldBe 0
        rule.onNodeWithText("Discard changes?").assertDoesNotExist()
    }

    private companion object {
        const val BODY = "Your rule set changes haven't been saved."

        val noOpActions =
            RuleSetEditorActions(
                onBack = {},
                onNameChanged = {},
                onDraftChanged = { _, _, _ -> },
                onAddEntry = { _, _, _ -> },
                onRemoveEntry = { _, _, _ -> },
                onMoveEarlier = {},
                onMoveLater = {},
                onDomainStrategyChanged = {},
                onSave = {},
            )
    }
}
