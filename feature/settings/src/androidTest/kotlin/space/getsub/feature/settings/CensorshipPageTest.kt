// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.NoiseMode
import space.getsub.core.model.NoisePacketType
import space.getsub.core.model.NoiseSettings
import space.getsub.core.ui.theme.SubspaceTheme
import space.getsub.feature.settings.pages.CensorshipPage

/**
 * The Censorship resistance page (M9-A spec §2.3): fields validate inline, an invalid draft is never
 * committed (Review Focus 4), and a valid one is committed when the page leaves (Review Focus 5).
 * camelCase names: the repo's DEX-040 constraint.
 */
class CensorshipPageTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The hosted state; each action applies its patch to it, as the ViewModel does to the stored value. */
    private val hosted = mutableStateOf(SettingsState())
    private val fragmentChanges = mutableListOf<FragmentSettings>()
    private val noiseChanges = mutableListOf<NoiseSettings>()

    private val actions =
        SettingsActions(
            onThemeChanged = {},
            onHwidEnabledChanged = {},
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
            onFragmentSettingsChanged = { patch ->
                val next = patch(hosted.value.fragment)
                fragmentChanges += next
                hosted.value = hosted.value.copy(fragment = next)
            },
            onNoiseSettingsChanged = { patch ->
                val next = patch(hosted.value.noise)
                noiseChanges += next
                hosted.value = hosted.value.copy(noise = next)
            },
        )

    private val lengthTag = "field-${R.string.settings_fragment_length}"
    private val packetTag = "field-${R.string.settings_noise_packet}"

    /** Hosts the page; returns the switch that removes it from composition, as leaving the page does. */
    private fun setContent(state: SettingsState): MutableState<Boolean> {
        hosted.value = state
        val show = mutableStateOf(true)
        composeRule.setContent {
            val visible by show
            val current by hosted
            SubspaceTheme {
                if (visible) CensorshipPage(state = current, actions = actions, onBack = {})
            }
        }
        return show
    }

    @Test
    fun offShowsNoFields() {
        setContent(SettingsState())

        composeRule.onNodeWithTag(lengthTag).assertDoesNotExist()
        // Off never claims "off everywhere": both switches name the provider's settings.
        composeRule.onAllNodesWithText("Off: your subscription's settings apply").assertCountEquals(2)
    }

    @Test
    fun theNextConnectionNoteShowsOnlyWhileConnected() {
        setContent(SettingsState())
        composeRule.onNodeWithText("Fragmentation changes from your next connection.").assertDoesNotExist()

        composeRule.runOnIdle { hosted.value = hosted.value.copy(sessionConnected = true) }

        composeRule.onNodeWithText("Fragmentation changes from your next connection.").assertIsDisplayed()
        composeRule.onNodeWithText("Noise changes from your next connection.").assertIsDisplayed()
    }

    @Test
    fun anInvalidLengthShowsTheErrorAndIsNotCommitted() {
        setContent(SettingsState(fragment = FragmentSettings.DEFAULT.copy(enabled = true)))

        composeRule.onNodeWithTag(lengthTag).performScrollTo().performTextReplacement("0-5")
        composeRule.onNodeWithTag(lengthTag).performImeAction()

        composeRule.onNodeWithText("The minimum length must be at least 1").assertIsDisplayed()
        composeRule.runOnIdle { fragmentChanges.shouldBeEmpty() }
    }

    @Test
    fun aValidEditCommitsWhenThePageLeavesWithoutDone() {
        val show = setContent(SettingsState(fragment = FragmentSettings.DEFAULT.copy(enabled = true)))

        composeRule.onNodeWithTag(lengthTag).performScrollTo().performTextReplacement("1-9")
        composeRule.runOnIdle { show.value = false }

        composeRule.runOnIdle { fragmentChanges.last().length shouldBe "1-9" }
    }

    /** Task 8 fix 1: the Length field's dispose commit must not switch Fragmentation back On. */
    @Test
    fun switchingOffWithAnUncommittedEditKeepsBoth() {
        setContent(SettingsState(fragment = FragmentSettings.DEFAULT.copy(enabled = true)))

        composeRule.onNodeWithTag(lengthTag).performScrollTo().performTextReplacement("1-9")
        composeRule.onNode(isToggleable() and hasText("Fragmentation")).performClick()

        composeRule.runOnIdle {
            hosted.value.fragment.enabled shouldBe false
            hosted.value.fragment.length shouldBe "1-9"
        }
        composeRule.onNodeWithTag(lengthTag).assertDoesNotExist()
    }

    @Test
    fun anInvalidHexPacketIsRefused() {
        setContent(
            SettingsState(
                noise =
                NoiseSettings.DEFAULT.copy(
                    enabled = true,
                    mode = NoiseMode.Fixed,
                    packetType = NoisePacketType.Hex,
                    packet = "0a0b",
                ),
            ),
        )

        composeRule.onNodeWithTag(packetTag).performScrollTo().performTextReplacement("zz")
        composeRule.onNodeWithTag(packetTag).performImeAction()

        composeRule.onNodeWithText("This packet does not match its type").assertIsDisplayed()
        composeRule.runOnIdle { noiseChanges.shouldBeEmpty() }
    }
}
