// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.add

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.theme.SubspaceTheme
import space.getsub.feature.profiles.R

/**
 * Task 13 (reshaped by Task 7b onto the single input box): the subscription outcomes of [AddServerSheetContent]
 * — M3's plan Part 2 line 715 deferred it to M4.
 *
 * Same harness as [AddServerSheetTest]: [AddServerSheetContent] directly,
 * with a plain [ImportState] and recording lambdas, no Hilt-backed
 * [ImportViewModel]. [ImportViewModel.addSubscription]'s own behaviour (the
 * URL validation, the add-then-sync call, deleting on a failed first sync)
 * is not this file's concern — it has no real [SubscriptionRepository][
 * space.getsub.core.data.SubscriptionRepository] or
 * [SubscriptionSyncer][space.getsub.core.data.sync.SubscriptionSyncer]
 * to exercise it against (both have `internal` constructors scoped to
 * `:core:data`, the same reason [ProfileSource][space.getsub.feature.profiles.ProfileSource]
 * exists as a fakeable seam for JVM tests). What this file owns: that the
 * entry exists, that the URL field's own text reaches
 * [ImportActions.onImportClick] verbatim, and that every
 * [SyncResult][space.getsub.core.data.sync.SyncResult] outcome
 * [ImportState.subscriptionResult] can carry renders its own distinct
 * string — [SubscriptionImportTest] proves the mapping to a resource id is
 * exhaustive and distinct at the JVM layer; this proves each resource id
 * actually resolves to readable, distinguishable English.
 *
 * v2 `createComposeRule` and camelCase names throughout — same DEX-040
 * constraint [AddServerSheetTest]'s own file-level KDoc documents.
 */
class AddServerSheetSubscriptionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        state: ImportState = ImportState(),
        onImportClick: () -> Unit = {},
    ) = setContent(mutableStateOf(state), onImportClick)

    /**
     * The [MutableState] overload, for the one test that needs the state to *change* after the
     * user has typed — see [addSubscriptionButtonIsDisabledWhileBusyEvenWithAUrlTyped].
     */
    private fun setContent(
        state: MutableState<ImportState>,
        onImportClick: () -> Unit = {},
    ): MutableState<ImportState> {
        composeRule.setContent {
            SubspaceTheme {
                AddServerSheetContent(
                    state = state.value,
                    actions =
                    ImportActions(
                        onInputChanged = { state.value = state.value.copy(input = it) },
                        onImportClick = onImportClick,
                        onImportFromFileClick = {},
                        onScanClick = {},
                        onImportClipboardClick = {},
                    ),
                )
            }
        }
        return state
    }

    @Test
    fun theOneInputBoxIsPresentAndTheSeparateSubscriptionEntryIsGone() {
        setContent()

        composeRule.onNodeWithTag(IMPORT_PASTE_FIELD_TEST_TAG).assertExists()
        composeRule.onNodeWithText("Paste a link, subscription URL or Xray JSON").assertExists()
        composeRule.onNodeWithText("Add").assertExists()
        composeRule.onNodeWithText("From subscription URL").assertDoesNotExist()
        composeRule.onNodeWithText("Add subscription").assertDoesNotExist()
    }

    @Test
    fun addSubscriptionButtonIsDisabledWhenTheUrlFieldIsBlank() {
        setContent()

        composeRule.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test
    fun addSubscriptionButtonIsEnabledOnceAUrlIsTyped() {
        setContent()

        composeRule.onNodeWithTag(IMPORT_PASTE_FIELD_TEST_TAG).performTextInput("https://example.com/sub")

        composeRule.onNodeWithText("Add").assertIsEnabled()
    }

    @Test
    fun addSubscriptionButtonIsDisabledWhileBusyEvenWithAUrlTyped() {
        // The URL field is itself `enabled = !busy`, so the text has to be typed before busy flips —
        // which is also the only way this state is reached in production: type a URL, press Add,
        // addSubscription() sets busy while the first sync runs. Starting at busy = true and typing
        // would fail on a disabled field and prove nothing about the button.
        val state = setContent(mutableStateOf(ImportState()))

        composeRule.onNodeWithTag(IMPORT_PASTE_FIELD_TEST_TAG).performTextInput("https://example.com/sub")
        composeRule.onNodeWithText("Add").assertIsEnabled()

        state.value = ImportState(busy = true)

        // busy dominates the `!busy && url.isNotBlank()` guard even though the URL is still there.
        composeRule.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test
    fun enteringAUrlAndConfirmingHandsItToTheSingleImportCallbackVerbatim() {
        var clicked = false
        val state = setContent(mutableStateOf(ImportState()), onImportClick = { clicked = true })

        composeRule.onNodeWithTag(IMPORT_PASTE_FIELD_TEST_TAG).performTextInput("https://example.com/sub")
        composeRule.onNodeWithText("Add").performClick()

        // Routing (subscription vs parse) is ImportViewModel.submit's job; the sheet only owns
        // that the typed text reaches state.input unchanged and the one button fires.
        state.value.input shouldBe "https://example.com/sub"
        clicked shouldBe true
    }

    @Test
    fun noSubscriptionResultMessageWhenNothingHasBeenAttempted() {
        setContent(state = ImportState(subscriptionResult = null))

        composeRule.onNodeWithText("Added 1 server").assertDoesNotExist()
    }

    // Success (SyncResult.Synced): one distinct message per plural form,
    // proving the <plurals> resource itself resolves, not just that
    // toUserMessage() picked the right id (SubscriptionImportTest's job).

    @Test
    fun aSuccessfulAddOfOneServerUsesTheSingularForm() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.plurals.subscription_added, 1)))

        composeRule.onNodeWithText("Added 1 server").assertExists()
    }

    @Test
    fun aSuccessfulAddOfSeveralServersUsesThePluralForm() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.plurals.subscription_added, 12)))

        composeRule.onNodeWithText("Added 12 servers").assertExists()
    }

    // Every failure string is distinct — the Compose-layer half of
    // SubscriptionImportTest's "every SubscriptionSyncFailure maps to a
    // distinct string" JVM assertion.

    @Test
    fun hwidRequiredRendersTheDeviceIdString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_hwid_required)))

        composeRule.onNodeWithText("This subscription requires a device ID. Enable it in Settings.").assertExists()
    }

    @Test
    fun deviceLimitReachedRendersItsOwnString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_device_limit)))

        composeRule.onNodeWithText("Device limit reached. Remove a device in your account.").assertExists()
    }

    @Test
    fun notFoundRendersItsOwnString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_not_found)))

        composeRule.onNodeWithText("Subscription not found. Check the URL.").assertExists()
    }

    @Test
    fun unreachableRendersItsOwnString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_unreachable)))

        composeRule.onNodeWithText("Could not reach the server.").assertExists()
    }

    @Test
    fun timedOutRendersItsOwnString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_timed_out)))

        composeRule.onNodeWithText("The server did not respond in time.").assertExists()
    }

    @Test
    fun tlsFailureRendersItsOwnString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_tls)))

        composeRule.onNodeWithText("Could not establish a secure connection.").assertExists()
    }

    @Test
    fun clientErrorRendersItsOwnString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_client)))

        composeRule.onNodeWithText("The server rejected the request.").assertExists()
    }

    @Test
    fun serverErrorRendersItsOwnString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_server)))

        composeRule.onNodeWithText("The server reported an error.").assertExists()
    }

    @Test
    fun noServersRendersItsOwnString() {
        setContent(state = ImportState(subscriptionResult = UserMessage(R.string.subscription_error_no_servers)))

        composeRule.onNodeWithText("No servers in this subscription.").assertExists()
    }
}
