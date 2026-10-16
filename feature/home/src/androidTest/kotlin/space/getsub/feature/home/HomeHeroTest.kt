// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.data.ProfileKind
import space.getsub.core.data.StoredProfile
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.Health
import space.getsub.core.model.Security
import space.getsub.core.model.StartupStage
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.VlessOutbound
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant

/**
 * M8.6 spec §4.1: the top half of Home. The action lives inside the blob
 * (amended 2026-10-04), so there is no "Connect" / "Disconnect" text node: the
 * blob is found by its content description (the state label) and its action
 * is its OnClick label.
 */
class HomeHeroTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun connectIsDisabledWithNothingToConnectTo() {
        setContent(HomeState(connection = ConnectionState.Disconnected, activeProfile = null))
        composeRule.onNodeWithContentDescription("Disconnected").assertIsNotEnabled().assert(clickLabel(null))
        composeRule.onNodeWithText("Disconnected · Choose a server first").assertIsDisplayed()
        composeRule.onNodeWithText("Tap the blob", substring = true).assertDoesNotExist()
    }

    @Test
    fun disconnectedBlobConnects() {
        var connects = 0
        composeRule.setContent {
            TestTheme {
                HomeScreenContent(
                    state = HomeState(connection = ConnectionState.Disconnected, activeProfile = profile),
                    actions = actions.copy(onConnect = { connects++ }),
                )
            }
        }
        composeRule.onNodeWithContentDescription("Disconnected").assertIsEnabled().assert(clickLabel("Connect"))
            .performClick()
        connects shouldBe 1
    }

    @Test
    fun connectedBlobDisconnects() {
        var disconnects = 0
        val connected = ConnectionState.Connected(sinceEpochMillis = 0L, socksPort = 1080, health = Health.Open)
        composeRule.setContent {
            TestTheme {
                HomeScreenContent(
                    state = HomeState(connection = connected, activeProfile = profile),
                    actions = actions.copy(onDisconnect = { disconnects++ }),
                )
            }
        }
        composeRule.onNodeWithContentDescription("Connected").assert(clickLabel("Disconnect")).performClick()
        disconnects shouldBe 1
    }

    @Test
    fun connectingOffersNoAction() {
        setContent(HomeState(connection = ConnectionState.Connecting(StartupStage.StartingCore)))
        composeRule.onNodeWithContentDescription("Starting core", substring = true)
            .assertIsNotEnabled()
            .assert(clickLabel(null))
        composeRule.onNodeWithText("Step 5 of 7", substring = true).assertIsDisplayed()
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

    /** The blob's OnClick action label: the word drawn inside it, or none. */
    private fun clickLabel(expected: String?) =
        SemanticsMatcher("OnClick label is $expected") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == expected
        }

    /** A connectable profile (VLESS over TCP), so Disconnected offers Connect. */
    private val profile =
        StoredProfile(
            id = 1,
            groupId = 1,
            kind = ProfileKind.TYPED,
            name = "Test server",
            protocol = "vless",
            address = "198.51.100.1",
            port = 443,
            transport = "tcp",
            outbound =
            VlessOutbound(
                address = "198.51.100.1",
                port = 443,
                uuid = "1e0f2a2e-6b2b-4b9a-9a3b-000000000000",
                flow = null,
                stream = StreamSettings(network = "tcp", security = Security.None),
            ),
            rawJson = null,
            lastConnectedAt = null,
            lastError = null,
        )

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
