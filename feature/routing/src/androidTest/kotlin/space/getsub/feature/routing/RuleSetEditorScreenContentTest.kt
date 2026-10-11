// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.model.BucketField
import space.getsub.core.model.RouteOutcome
import space.getsub.core.model.RuleBucket
import space.getsub.core.ui.theme.SubspaceTheme

/**
 * Task 16's own fix round 1 (Finding 5): this task shipped zero Compose tests, even non-running
 * ones, unlike Task 15's [RoutingListScreenContentTest] — this closes that gap for the same
 * layout/rendering facts a JVM state test cannot see: whether the category picker actually shows
 * or hides, whether a rejected entry's text and reason actually render where the user is typing.
 * [RuleSetEditorViewModelTest] is the runnable proof of the underlying state transitions; this is
 * the rendering half, same split [RoutingListScreenContentTest]'s own KDoc documents.
 *
 * **Not run in this environment** — no Android device or emulator is reachable here. Compiled and
 * left for a `connectedDebugAndroidTest` pass on a real device or CI runner.
 *
 * camelCase test names throughout, same DEX-040 constraint every other instrumented test in this
 * repo documents.
 */
class RuleSetEditorScreenContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val noOpActions =
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

    private fun setContent(
        state: RuleSetEditorState,
        actions: RuleSetEditorActions = noOpActions,
    ) {
        composeRule.setContent {
            SubspaceTheme {
                RuleSetEditorContent(state = state, actions = actions)
            }
        }
    }

    @Test
    fun aLoadingDraftShowsTheSpinnerAndNothingElse() {
        setContent(RuleSetEditorState(loading = true))

        composeRule.onNodeWithTag(RULE_SET_EDITOR_LOADING_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun anUnnamedDraftHasADisabledSaveButton() {
        setContent(RuleSetEditorState(loading = false, name = ""))

        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun aNamedDraftHasAnEnabledSaveButton() {
        setContent(RuleSetEditorState(loading = false, name = "ads"))

        composeRule.onNodeWithText("Save").assertIsEnabled()
    }

    // Finding 5: the category picker's show/hide rule was pinned by nothing before this file.
    @Test
    fun theCategoryPickerOnlyAppearsForAFieldThatHasCategories() {
        setContent(
            RuleSetEditorState(
                loading = false,
                name = "ads",
                siteCategories = listOf(GeoCategory("category-ads-all", 42)),
                ipCategories = emptyList(),
            ),
        )

        // Six sections exist (BLOCK/PROXY/DIRECT × SITES/IPS); only the three SITES ones offer
        // Browse — the three IPS ones must not, since ipCategories is empty.
        //
        // M8.6 Part 4 Task 6: the button reads "Browse" (the design's copy), not "Browse
        // categories". An empty field now collapses to its Add button (fix S5), and Browse stays
        // beside that button, so all three SITES fields still offer it without being opened.
        composeRule.onAllNodesWithText("Browse").assertCountEquals(3)
    }

    @Test
    fun noCategoriesMeansTheBrowseAffordanceNeverAppears() {
        setContent(RuleSetEditorState(loading = false, name = "ads"))

        composeRule.onNodeWithText("Browse").assertDoesNotExist()
    }

    // Finding 4: a rejected entry must keep its text and show its reason on the field it belongs
    // to, not clear silently and report off-screen.
    //
    // Fix round 3 (device run): typing into a SITES field instead proved nothing — "999.1.1.1" is
    // not malformed for SITES under RoutingEntries.siteProblem, so no reason was ever composed.
    // This types into the first IPS field (BLOCK×IPS), where 999 is not a valid IPv4 octet.
    //
    // M8.6 Part 4 Task 6, three changes of selector, same assertion:
    // - The draft now lives in RuleSetEditorState (so isDirty can count it, fix A11) rather than
    //   in the field, so a static state would never show what was typed. The harness below feeds
    //   onDraftChanged back into the state, as RuleSetEditorViewModel.setDraft does.
    // - An empty field is collapsed to "Add IPs" (fix S5); BLOCK is first in the default order, so
    //   [0] opens BLOCK×IPS, and the field is then found by its tag rather than its old label.
    // - The design's IP check accepts "999.1.1.1" (it only counts digits), so the message shown is
    //   still EntryProblem's — R4 runs it second and it rejects the octet.
    @Test
    fun typingAMalformedAddressKeepsTheTextAndShowsTheReasonInline() {
        var added: String? = null
        composeRule.setContent {
            var state by remember { mutableStateOf(RuleSetEditorState(loading = false, name = "ads")) }
            SubspaceTheme {
                RuleSetEditorContent(
                    state = state,
                    actions =
                    noOpActions.copy(
                        onDraftChanged = { outcome, field, text ->
                            state = state.copy(drafts = state.drafts + ((outcome to field) to text))
                        },
                        onAddEntry = { _, _, entry -> added = entry },
                    ),
                )
            }
        }

        // Same as above: the editor now clears the navigation bar, so Save floats higher and the
        // first Add IPs is under it at scroll 0. Scroll it clear first, as a user would.
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeUp(startY = centerY, endY = centerY - 600f) }
        composeRule.onAllNodesWithText("Add IPs")[0].performScrollTo().performClick()
        composeRule
            .onNodeWithTag(ruleSetEntryFieldTag(RouteOutcome.BLOCK, BucketField.IPS))
            .performTextInput("999.1.1.1")

        composeRule.onNodeWithText("999.1.1.1").assertIsDisplayed()
        composeRule.onNodeWithText("Not a valid address or CIDR range").performScrollTo().assertIsDisplayed()
        added.shouldBeNull()
    }

    // M8.6 spec §4.6 and plan ruling R4, rendered: an entry the design's checks reject shows the
    // design's message on its field. Read from state, so no typing is needed.
    @Test
    fun aDraftTheDesignRejectsShowsTheDesignsMessage() {
        setContent(
            RuleSetEditorState(
                loading = false,
                name = "ads",
                drafts = mapOf((RouteOutcome.BLOCK to BucketField.SITES) to "geoip:cn"),
            ),
        )

        composeRule.onNodeWithText("Use geosite: for sites").performScrollTo().assertIsDisplayed()
        // Fix round 1, minor 8: the reason is on the field itself too, for TalkBack.
        composeRule
            .onNodeWithTag(ruleSetEntryFieldTag(RouteOutcome.BLOCK, BucketField.SITES), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Use geosite: for sites"))
    }

    // Fix round 1, minor 7: while the write is in flight nothing can be edited, so nothing typed
    // then is lost when the screen leaves on `saved`.
    @Test
    fun whileSavingNothingCanBeEdited() {
        setContent(
            RuleSetEditorState(
                loading = false,
                name = "ads",
                saving = true,
                buckets = mapOf(RouteOutcome.BLOCK to RuleBucket(sites = listOf("example.com"))),
            ),
        )

        composeRule.onNodeWithText("Saving…").assertIsNotEnabled()
        composeRule.onNodeWithText("ads").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Remove entry").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Move Proxy earlier").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Move Proxy later").assertIsNotEnabled()
        composeRule.onNodeWithTag(ruleSetEntryFieldTag(RouteOutcome.BLOCK, BucketField.SITES)).assertIsNotEnabled()
        composeRule.onAllNodesWithText("Add IPs")[0].assertIsNotEnabled()
        composeRule.onNodeWithText("As is").assertIsNotEnabled()
    }

    // Fix S5: an empty field is one Add button until opened; one with entries shows its input.
    @Test
    fun emptyFieldsCollapseToAnAddButtonThatOpensTheInput() {
        setContent(
            RuleSetEditorState(
                loading = false,
                name = "ads",
                buckets = mapOf(RouteOutcome.BLOCK to RuleBucket(sites = listOf("example.com"))),
            ),
        )

        composeRule.onNodeWithTag(ruleSetEntryFieldTag(RouteOutcome.BLOCK, BucketField.SITES)).assertExists()
        composeRule.onNodeWithTag(ruleSetEntryFieldTag(RouteOutcome.BLOCK, BucketField.IPS)).assertDoesNotExist()
        composeRule.onAllNodesWithText("Add sites").assertCountEquals(2)
        composeRule.onAllNodesWithText("Add IPs").assertCountEquals(3)

        // BLOCK x IPS sits at the bottom of the first screenful, under the floating Save button:
        // inside the viewport, so performScrollTo has nothing to do, yet a click there lands on
        // Save (device run, Pixel 8: Save 2200-2347px over Add IPs 2211-2316px, scroll value 0).
        // Scroll it clear first, as a user would.
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeUp(startY = centerY, endY = centerY - 600f) }
        composeRule.onAllNodesWithText("Add IPs")[0].performScrollTo().performClick()

        composeRule.onNodeWithTag(ruleSetEntryFieldTag(RouteOutcome.BLOCK, BucketField.IPS)).assertExists()
        composeRule.onAllNodesWithText("Add IPs").assertCountEquals(2)
    }

    @Test
    fun anExistingEntryHasARemoveControl() {
        setContent(
            RuleSetEditorState(
                loading = false,
                name = "ads",
                buckets = mapOf(RouteOutcome.BLOCK to RuleBucket(sites = listOf("example.com"))),
            ),
        )

        composeRule.onNodeWithText("example.com").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Remove entry")[0].assertIsDisplayed()
    }

    // Fix round 3 (device run): this test asserted straight off `setContent`, with no scroll.
    // The form has six full entry sections above this message and the Save button below it;
    // confirmed on-device that the message node genuinely composes with real text but at
    // y ≈ 3297–3393px while the test window's visible root is only 0–2400px tall — a real node,
    // off-screen, not "never rendered". `performScrollTo()` brings it into the scrollable
    // Column's visible viewport before asserting, the same way a user would scroll to see it.
    @Test
    fun aNameConflictProblemIsShownNearSave() {
        setContent(
            RuleSetEditorState(
                loading = false,
                name = "ads",
                saveProblem = SaveProblem.NameConflict("ads"),
            ),
        )

        val message = "A rule set named \"ads\" already exists. Choose a different name."
        composeRule.onNodeWithText(message).performScrollTo().assertIsDisplayed()
    }

    // Fix round 3 (device run): the Save button used to compose at the end of the scrolling form,
    // off-screen, where `performClick()` landed nowhere — so this scrolled to it first.
    //
    // M8.6 Part 4 Task 6: Save now floats over the bottom of the screen, outside the scrolling
    // form (the design's sticky save bar), so it is always on screen. `performScrollTo()` is
    // dropped because the button has no scrollable parent to scroll; the tap still has to reach
    // onSave, which is what this asserts.
    @Test
    fun tappingSaveInvokesTheCallback() {
        var saved = false
        setContent(
            state = RuleSetEditorState(loading = false, name = "ads"),
            actions = noOpActions.copy(onSave = { saved = true }),
        )

        composeRule.onNodeWithText("Save").assertIsDisplayed().performClick()

        saved shouldBe true
    }

    // A Save refused over an unaddable draft asks the screen to show that field: it takes focus
    // (which also scrolls it into view) and the request is reported handled.
    @Test
    fun aRevealRequestFocusesTheNamedFieldAndIsHandled() {
        var handled = 0
        setContent(
            RuleSetEditorState(
                loading = false,
                name = "ads",
                drafts = mapOf((RouteOutcome.BLOCK to BucketField.IPS) to "999.1.1.1"),
                revealRequest = RevealRequest(RouteOutcome.BLOCK, BucketField.IPS, token = 1),
            ),
            noOpActions.copy(onRevealHandled = { handled++ }),
        )

        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ruleSetEntryFieldTag(RouteOutcome.BLOCK, BucketField.IPS)).assertIsFocused()
        handled shouldBe 1
    }
}
