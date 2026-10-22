// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.hub

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.data.ThemePreference
import space.getsub.core.ui.theme.SubspaceTheme
import space.getsub.feature.settings.SettingsActions
import space.getsub.feature.settings.SettingsNavigation
import space.getsub.feature.settings.SettingsPageId
import space.getsub.feature.settings.SettingsState

/** The Settings hub (M8.6 spec §4.4, §5.2). camelCase names: the repo's DEX-040 constraint. */
class SettingsHubTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var breakdownChange: Boolean? = null
    private var openedPage: SettingsPageId? = null

    private val actions =
        SettingsActions(
            onThemeChanged = {},
            onPingModeChanged = {},
            onPingCheckUrlChanged = {},
            onPingTimeoutChanged = {},
            onPingOnLaunchChanged = {},
            onPingOnLaunchMeteredChanged = {},
            onDnsTransportChanged = {},
            onDnsAddressChanged = {},
            onDnsBootstrapIpChanged = {},
            onGeoSourceSelected = {},
            onGeoUpdateNow = {},
            onAddCustomGeoSource = { _, _, _ -> },
            onGeoRefreshOnMeteredChanged = {},
            onRemoveCustomGeoSource = {},
            onPerTagBreakdownChanged = { breakdownChange = it },
        )

    private val navigation =
        SettingsNavigation(
            onNavigateToRouting = {},
            onNavigateToPerApp = {},
            onNavigateToLogViewer = {},
            onOpenPage = { openedPage = it },
        )

    /**
     * M8.6 plan review focus 1: a setting changed on a sub-page shows on the hub at once. Both read
     * the same Room-backed state, so the hub only has to re-render from a new [SettingsState].
     */
    @Test
    fun aHubRowValueFollowsTheState() {
        var state by mutableStateOf(SettingsState(theme = ThemePreference.System))
        composeRule.setContent {
            SubspaceTheme { SettingsHubContent(state = state, actions = actions, navigation = navigation) }
        }

        composeRule.onNodeWithText("System").assertIsDisplayed()

        state = state.copy(theme = ThemePreference.Dark)

        composeRule.onNodeWithText("Dark").assertIsDisplayed()
        composeRule.onNodeWithText("System").assertDoesNotExist()
    }

    @Test
    fun aPageRowOpensItsPage() {
        composeRule.setContent {
            SubspaceTheme { SettingsHubContent(state = SettingsState(), actions = actions, navigation = navigation) }
        }

        composeRule.onNodeWithText("DNS").performScrollTo().performClick()

        openedPage shouldBe SettingsPageId.Dns
    }

    /**
     * The breakdown toggle's semantics (M8.6 spec §7.3): it is one switch named for its own label,
     * reflects the stored value, and reports a change upward. Its supporting text is the security
     * disclosure, word for word.
     */
    @Test
    fun theBreakdownToggleIsASwitchNamedForItsLabel() {
        var state by mutableStateOf(SettingsState(perTagBreakdown = false))
        composeRule.setContent {
            SubspaceTheme { SettingsHubContent(state = state, actions = actions, navigation = navigation) }
        }

        val toggle = composeRule.onNode(isToggleable() and hasText("Traffic by server"))
        toggle.performScrollTo().assertIsOff()
        composeRule.onNode(isToggleable() and hasText(BREAKDOWN_DISCLOSURE)).assertExists()

        toggle.performClick()
        breakdownChange shouldBe true

        state = state.copy(perTagBreakdown = true)
        toggle.assertIsOn()
    }

    @Test
    fun theBreakdownNoticeShowsOnlyWhileConnected() {
        var state by mutableStateOf(SettingsState(sessionConnected = false))
        composeRule.setContent {
            SubspaceTheme { SettingsHubContent(state = state, actions = actions, navigation = navigation) }
        }

        composeRule.onNode(hasText(NOTICE, substring = true)).assertDoesNotExist()

        state = state.copy(sessionConnected = true)

        composeRule.onNode(isToggleable() and hasText(NOTICE)).assertExists()
    }

    private companion object {
        const val BREAKDOWN_DISCLOSURE =
            "Show how much traffic went through the proxy versus direct. While connected, this opens a " +
                "diagnostics port that other apps on your device can also read — leave it off unless you need it."
        const val NOTICE = "⚠ Applies to your next connection"
    }
}
