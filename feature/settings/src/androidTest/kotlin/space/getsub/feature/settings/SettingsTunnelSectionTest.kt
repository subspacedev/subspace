// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.model.PerAppMode
import space.getsub.core.model.PerAppSelection
import space.getsub.core.ui.theme.SubspaceTheme
import space.getsub.feature.settings.hub.TunnelCard
import space.getsub.feature.settings.hub.TunnelCardActions

/**
 * M8 spec §7.1, M8 spec §7.2, M8.6 spec §5.1: the Tunnel card — always-on VPN as a link (never a
 * switch, since this app cannot own that system state), the boot-autostart and kill switches, and
 * the two conditional second lines.
 *
 * Moved onto the card in M8.6 Part 4 Task 3. The kill switch's title is now "Kill switch" (it was
 * "Block traffic when disconnected"); each assertion means what it meant. A `SwitchRow` is one
 * toggleable node carrying its own label, so "the switch belonging to this label" is now
 * `isToggleable() and hasText(label)` rather than a toggle under a labelled ancestor.
 */
class SettingsTunnelSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setCard(
        state: SettingsState,
        onBootAutostartChange: (Boolean) -> Unit = {},
        onOpenVpnSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            SubspaceTheme {
                TunnelCard(
                    state = state,
                    actions =
                    TunnelCardActions(
                        onBootAutostartChange = onBootAutostartChange,
                        onFailClosedChange = {},
                        onLightweightModeChange = {},
                        onOpenVpnSettings = onOpenVpnSettings,
                        onOpenBatterySettings = {},
                    ),
                )
            }
        }
    }

    @Test
    fun alwaysOnIsALinkAndNotASwitch() {
        var opened = false
        setCard(tunnelState(), onOpenVpnSettings = { opened = true })

        composeRule.onNodeWithText("Always-on VPN").performClick()

        // M8 spec §7.1: the app cannot enable always-on or its lockdown; both are
        // system settings. A switch here would lie about who owns the state.
        opened shouldBe true

        // The click assertion above only proves the row is clickable — a regression that kept
        // it clickable while also adding a Switch to the row would still pass it. Assert the
        // absence directly: the row itself is not toggleable, and no toggle sits under it.
        composeRule
            .onNode(isToggleable() and (hasText("Always-on VPN") or hasAnyAncestor(hasText("Always-on VPN"))))
            .assertDoesNotExist()
    }

    @Test
    fun failClosedRendersItsStoredValue() {
        setCard(tunnelState(failClosed = true))

        composeRule.onNodeWithText("Kill switch").assertIsDisplayed()
        composeRule.onNode(isToggleable() and hasText("Kill switch")).assertIsOn()
    }

    @Test
    fun togglingBootAutostartReportsUpward() {
        var toggled: Boolean? = null
        setCard(tunnelState(bootAutostart = false), onBootAutostartChange = { toggled = it })

        composeRule.onNode(isToggleable() and hasText("Connect at startup")).performClick()

        toggled shouldBe true
    }

    /**
     * M8.6 spec §5.1: the kill switch's per-app line shows only while per-app is not Off — with
     * the kill switch off as well, since the line describes per-app, not the switch's position.
     */
    @Test
    fun killSwitchPerAppLineFollowsPerAppMode() {
        var state by mutableStateOf(tunnelState(failClosed = false))
        composeRule.setContent {
            SubspaceTheme {
                TunnelCard(state = state, actions = TunnelCardActions({}, {}, {}, {}, {}))
            }
        }

        composeRule.onNodeWithText(PER_APP_LINE).assertDoesNotExist()

        state = state.copy(perApp = PerAppSelection(PerAppMode.AllowList, setOf("a.b")))
        composeRule.onNodeWithText(PER_APP_LINE).assertIsDisplayed()

        state = state.copy(failClosed = true)
        composeRule.onNodeWithText(PER_APP_LINE).assertIsDisplayed()

        state = state.copy(perApp = PerAppSelection.OFF)
        composeRule.onNodeWithText(PER_APP_LINE).assertDoesNotExist()
    }

    /** M8.6 plan review focus 5: lightweight mode's line shows only while connected. */
    @Test
    fun lightweightLineShowsOnlyWhileConnected() {
        var state by mutableStateOf(tunnelState().copy(lightweightMode = true))
        composeRule.setContent {
            SubspaceTheme {
                TunnelCard(state = state, actions = TunnelCardActions({}, {}, {}, {}, {}))
            }
        }

        composeRule.onNodeWithText(LIGHTWEIGHT_LINE).assertDoesNotExist()

        state = state.copy(sessionConnected = true)
        composeRule.onNodeWithText(LIGHTWEIGHT_LINE).assertIsDisplayed()
    }

    private fun tunnelState(bootAutostart: Boolean = false, failClosed: Boolean = true) =
        SettingsState(bootAutostart = bootAutostart, failClosed = failClosed)

    private companion object {
        const val PER_APP_LINE = "Apps excluded from the tunnel are unaffected."
        const val LIGHTWEIGHT_LINE = "The session log changes from your next connection."
    }
}
