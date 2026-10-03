// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.ui.theme.SubspaceTheme

/**
 * M8 spec §7.3 / ARCHITECTURE.md §5.5: [ConnectionState.Reconnecting] is neither [ConnectionState.Connected]
 * nor [ConnectionState.Failed], and rendering it as either is a lie the user acts on.
 */
class HomeReconnectingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reconnectingIsNotRenderedAsConnected() {
        setContent(
            homeState(
                connection = ConnectionState.Reconnecting(FailureReason.CoreStartFailed, attempt = 2, blocked = false),
            ),
        )

        composeRule.onNodeWithText("Reconnecting").assertIsDisplayed()
        composeRule.onNodeWithText("Connected").assertDoesNotExist()
    }

    @Test
    fun reconnectingIsNotRenderedAsFailed() {
        setContent(
            homeState(
                connection = ConnectionState.Reconnecting(FailureReason.CoreStartFailed, attempt = 2, blocked = false),
            ),
        )

        // "Disconnected" alone would not catch this: ConnectionState.Failed never renders that
        // literal string — it renders reason.labelRes(), which for CoreStartFailed is exactly
        // "The core failed to start". That is the text a Reconnecting-rendered-as-Failed
        // regression would actually put on screen, so that is what must be absent.
        composeRule.onNodeWithText("The core failed to start", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Reconnecting").assertIsDisplayed()
    }

    /**
     * M8 spec §7.3, and ruling R23. A `Retryable` reason retries for as long as a network
     * exists, so this state has no bound; with the kill switch on (the default) the user has
     * no connectivity while it retries. Home is therefore the exit, and this asserts both taps
     * actually reach [HomeActions.onDisconnect].
     *
     * Several independent refusals had to be lifted together for this to pass — [HomeState
     * .canDisconnect] excluded `Reconnecting`, and the primary action and the blob's enabled
     * state each had to offer it — so a regression restoring any one of them fails here. The
     * exit is a visible Disconnect button and also the blob's click label.
     */
    @Test
    fun reconnectingOffersAWorkingDisconnect() {
        var disconnects = 0
        val state =
            homeState(
                connection = ConnectionState.Reconnecting(FailureReason.CoreStartFailed, attempt = 2, blocked = false),
            )
        composeRule.setContent {
            SubspaceTheme {
                HomeScreenContent(state = state, actions = actions.copy(onDisconnect = { disconnects++ }))
            }
        }

        composeRule.onNodeWithText("Disconnect").performClick()
        disconnects shouldBe 1

        // The blob's content description is the state label; its click label is the action.
        composeRule.onNodeWithContentDescription("Reconnecting").performClick()
        disconnects shouldBe 2
    }

    @Test
    fun blockedReconnectingSaysTrafficIsBlocked() {
        setContent(
            homeState(
                connection = ConnectionState.Reconnecting(
                    FailureReason.CoreStartFailed,
                    attempt = 2,
                    blocked = true,
                ),
            ),
        )

        composeRule.onNodeWithText("Blocked").assertIsDisplayed()
        // The ARCHITECTURE.md §5.5 banner.
        composeRule.onNodeWithText("Kill switch is holding traffic", substring = true).assertIsDisplayed()
    }

    @Test
    fun openReconnectingSaysTrafficIsNotProtected() {
        setContent(
            homeState(
                connection = ConnectionState.Reconnecting(
                    FailureReason.CoreStartFailed,
                    attempt = 2,
                    blocked = false,
                ),
            ),
        )

        composeRule.onNodeWithText("Traffic is not protected", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Kill switch is holding traffic", substring = true).assertDoesNotExist()
    }

    private fun setContent(state: HomeState) {
        composeRule.setContent {
            SubspaceTheme {
                HomeScreenContent(state = state, actions = actions)
            }
        }
    }

    private fun homeState(connection: ConnectionState) = HomeState(connection = connection)

    private val actions =
        HomeActions(
            onConnect = {},
            onDisconnect = {},
            onNavigateToServers = {},
            onAddServer = {},
            onTestLatency = {},
            onOpenRouting = {},
            onOpenPerApp = {},
            onOpenSessionLog = {},
        )
}
