// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.subscription

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.data.RedirectIssue
import space.getsub.core.data.RedirectTrust
import space.getsub.core.data.ResolverTrust
import space.getsub.core.data.preresolve.ProviderResolver
import space.getsub.core.ui.theme.SubspaceTheme

class SubscriptionDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val pinnedInterval =
        DirectiveRow(
            kind = DirectiveRowKind.Hours,
            labelRes = space.getsub.feature.profiles.R.string.subscription_row_interval_label,
            row = SettingRowState("profile-update-interval", "24", declinedProviderValue = "6", isPinned = true),
        )

    private fun setContent(
        state: SubscriptionDetailState = detailState(),
        actions: SubscriptionDetailActions = noOpActions(),
    ) {
        composeRule.setContent {
            SubspaceTheme { SubscriptionDetailContent(state = state, actions = actions) }
        }
    }

    @Test
    fun pinnedValueShowsTheDeclinedProviderSuggestion() {
        setContent()

        composeRule.onNodeWithText("Provider suggests 6 h · you pinned 24 h").assertExists()
    }

    @Test
    fun theFullSubscriptionUrlIsNeverRendered() {
        setContent()

        composeRule.onNodeWithText("https://panel.example/sub/secret-token").assertDoesNotExist()
        composeRule.onNodeWithText("https://panel.example/…").assertExists()
    }

    @Test
    fun deletingAsksForConfirmationBeforeInvokingTheAction() {
        var deleteCalls = 0
        setContent(actions = noOpActions(onDeleteConfirmed = { deleteCalls++ }))

        composeRule.onNodeWithText("Delete subscription").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete “Provider”?").assertExists()
        deleteCalls shouldBe 0

        composeRule.onNodeWithText("Delete").performClick()
        deleteCalls shouldBe 1
    }

    @Test
    fun tappingUnpinInvokesTheRowAction() {
        var unpinnedKey: String? = null
        setContent(actions = noOpActions(onUnpinRow = { unpinnedKey = it }))

        composeRule.onNodeWithContentDescription("Use provider value").performScrollTo().performClick()

        unpinnedKey shouldBe "profile-update-interval"
    }

    @Test
    fun theRedirectRequestNamesTheHostAndAllowInvokesTheAction() {
        var allowCalls = 0
        setContent(
            state = detailState().copy(redirectTargetHosts = listOf("new.example")),
            actions = noOpActions(onAllowRedirects = { allowCalls++ }),
        )

        composeRule
            .onNodeWithText(
                "Your provider wants permission to move or redirect this subscription — to new.example",
            ).assertExists()
        composeRule.onNodeWithText("Allow").performClick()
        allowCalls shouldBe 1
    }

    @Test
    fun theResolverRequestNamesTheHostAndAllowInvokesTheAction() {
        var allowCalls = 0
        setContent(
            state =
            detailState().copy(
                resolverProvider = ProviderResolver.Valid("https://dns.example/q", "dns.example", null),
                resolverTrust = ResolverTrust.Unasked,
            ),
            actions = noOpActions(onAllowResolver = { allowCalls++ }),
        )

        composeRule
            .onNodeWithText("Your provider wants to look up server addresses through dns.example")
            .assertExists()
        composeRule.onNodeWithText("Allow").performClick()
        allowCalls shouldBe 1
    }

    @Test
    fun anIgnoredInsecureFlagIsExplained() {
        setContent(state = detailState().copy(insecureIgnored = true))

        composeRule
            .onNodeWithText("Certificate checks stay on — insecure in the link was ignored")
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun aGlobalFragmentationSettingIsNamedUnderTheFetchField() {
        setContent(state = detailState().copy(globalFragmentationOn = true))

        composeRule.onNodeWithText("Your Fragmentation settings apply").performScrollTo().assertExists()
    }

    @Test
    fun aNewDomainSupersededByNewUrlSaysSo() {
        val superseded = RedirectIssue("new-domain", reason = null, collides = false, superseded = true)
        setContent(state = detailState().copy(redirectIssues = listOf(superseded)))

        composeRule.onNodeWithText("new-domain ignored: new-url takes precedence").performScrollTo().assertExists()
    }

    @Test
    fun deniedRedirectsAreNamedWhenTheProviderAsks() {
        setContent(
            state = detailState().copy(redirectTrust = RedirectTrust.Denied, redirectTargetHosts = listOf("n.example")),
        )

        composeRule
            .onNodeWithText("Your provider's redirects are not followed — you didn't allow them")
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun aMoveWithoutATimeNamesOnlyTheOldHost() {
        setContent(state = detailState().copy(movedFromHost = "old.example", movedAt = null))

        composeRule.onNodeWithText("Moved from old.example").performScrollTo().assertExists()
    }

    private fun noOpActions(
        onDeleteConfirmed: () -> Unit = {},
        onUnpinRow: (String) -> Unit = {},
        onAllowRedirects: () -> Unit = {},
        onAllowResolver: () -> Unit = {},
    ) =
        SubscriptionDetailActions(
            onBack = {},
            onRefreshNow = {},
            onDismissRefreshResult = {},
            onPinRow = { _, _ -> },
            onUnpinRow = onUnpinRow,
            onHwidEnabledChanged = {},
            onUserAgentOverrideChanged = {},
            onDeleteConfirmed = onDeleteConfirmed,
            onAllowRedirects = onAllowRedirects,
            onDenyRedirects = {},
            onRedirectsAllowedChanged = {},
            onFrontingSaved = { _, _ -> },
            onLinkFragmentSaved = {},
            onAllowResolver = onAllowResolver,
        )

    private fun detailState() =
        SubscriptionDetailState(
            subscriptionId = 7L,
            loading = false,
            groupName = "Provider",
            profileCount = 2,
            redactedUrl = "https://panel.example/…",
            rows = listOf(pinnedInterval),
        )
}
