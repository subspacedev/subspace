// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.StartupStage
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant

/** M8.6 spec §4.1: the top half of Home. */
class HomeHeroTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun connectIsDisabledWithNothingToConnectTo() {
        setContent(HomeState(connection = ConnectionState.Disconnected, activeProfile = null))
        composeRule.onNodeWithText("Connect").assertIsNotEnabled()
        composeRule.onNodeWithText("Choose a server first").assertIsDisplayed()
        composeRule.onNodeWithText("Tap the blob", substring = true).assertDoesNotExist()
    }

    @Test
    fun connectingOffersNoAction() {
        setContent(HomeState(connection = ConnectionState.Connecting(StartupStage.StartingCore)))
        composeRule.onNodeWithText("Disconnect").assertDoesNotExist()
        composeRule.onNodeWithText("Connect").assertDoesNotExist()
        composeRule.onNodeWithText("Step 4 of 6").assertIsDisplayed()
    }

    @Test
    fun chipsOpenTheirScreens() {
        var routing = 0
        var perApp = 0
        composeRule.setContent {
            TestTheme {
                HomeScreenContent(
                    state = HomeState(activeRuleSetName = "Smart route"),
                    actions = actions.copy(onOpenRouting = { routing++ }, onOpenPerApp = { perApp++ }),
                )
            }
        }
        composeRule.onNodeWithText("Smart route").performClick()
        composeRule.onNodeWithText("All apps tunnelled").performClick()
        routing shouldBe 1
        perApp shouldBe 1
    }

    @Test
    fun historyOpensTheSessionLog() {
        var opened = 0
        composeRule.setContent {
            TestTheme { HomeScreenContent(HomeState(), actions.copy(onOpenSessionLog = { opened++ })) }
        }
        composeRule.onNodeWithContentDescription("Session log").performClick()
        opened shouldBe 1
    }

    private fun setContent(state: HomeState) {
        composeRule.setContent { TestTheme { HomeScreenContent(state = state, actions = actions) } }
    }

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

    /**
     * Reduced motion: ConnectBlob's drift and shape-cycle loops never finish, so under
     * normal motion Compose never reaches idle and `setContent` times out. The loops are
     * correct production behaviour (M8.6 spec §2.6); the tests only need a settled frame.
     */
    @Composable
    private fun TestTheme(content: @Composable () -> Unit) =
        SubspacePreviewTheme(ThemeVariant.FriendlyLight, reducedMotion = true, content = content)
}
