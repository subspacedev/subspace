// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

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
import space.getsub.core.ui.theme.SubspaceTheme

/** M8.6 spec §4.2: the top half of Home. */
class HomeHeroTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun connectIsDisabledWithNothingToConnectTo() {
        setContent(HomeState(connection = ConnectionState.Disconnected, activeProfile = null))
        composeRule.onNodeWithText("Connect").assertIsNotEnabled()
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
            SubspaceTheme {
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
            SubspaceTheme { HomeScreenContent(HomeState(), actions.copy(onOpenSessionLog = { opened++ })) }
        }
        composeRule.onNodeWithContentDescription("Session log").performClick()
        opened shouldBe 1
    }

    private fun setContent(state: HomeState) {
        composeRule.setContent { SubspaceTheme { HomeScreenContent(state = state, actions = actions) } }
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
}
