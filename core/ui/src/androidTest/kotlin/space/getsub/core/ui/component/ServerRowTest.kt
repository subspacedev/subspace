// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.theme.SubspaceTheme

class ServerRowTest {
    @get:Rule
    val rule = createComposeRule()

    /** Turn 4 fix A7: re-testing latency must not also select the server. */
    @Test
    fun tappingTheLatencyPillTestsWithoutSelecting() {
        var selected = 0
        var tested = 0
        rule.setContent {
            SubspaceTheme(dynamicColor = false) {
                ServerRow(
                    flag = "🇩🇪", name = "Frankfurt Alpha", detail = "VLESS · REALITY · TCP", latencyText = "42 ms",
                    selected = false, enabled = true, rank = 1, note = null,
                    onSelect = { selected++ }, onTestLatency = { tested++ }, onEdit = {},
                )
            }
        }
        rule.onNodeWithText("42 ms").performClick()
        tested shouldBe 1
        selected shouldBe 0
        rule.onNodeWithText("Frankfurt Alpha").performClick()
        selected shouldBe 1
    }

    /** Fix S2: the reason comes first on an unsupported server. */
    @Test
    fun anUnsupportedRowShowsItsReason() {
        var selected = 0
        var tested = 0
        var edited = 0
        rule.setContent {
            SubspaceTheme(dynamicColor = false) {
                ServerRow(
                    flag = "🇫🇮", name = "Helsinki Edge", detail = "TROJAN · WS · TLS", latencyText = "—",
                    selected = false, enabled = false, rank = null, note = "can't connect with this build",
                    onSelect = { selected++ }, onTestLatency = { tested++ }, onEdit = { edited++ },
                )
            }
        }
        rule.onNodeWithText("can't connect with this build").assertExists()
        rule.onNodeWithContentDescription("Edit Helsinki Edge").assertExists()
        // enabled = false disables the latency pill only; selecting and editing still work.
        rule.onNodeWithText("—").performClick()
        tested shouldBe 0
        rule.onNodeWithText("Helsinki Edge").performClick()
        selected shouldBe 1
        rule.onNodeWithContentDescription("Edit Helsinki Edge").performClick()
        edited shouldBe 1
    }

    /** The active server is exposed to accessibility, not only drawn in a tint. */
    @Test
    fun theSelectedStateIsExposedInSemantics() {
        rule.setContent {
            SubspaceTheme(dynamicColor = false) {
                Column {
                    ServerRow(
                        flag = "🇩🇪", name = "Frankfurt Alpha", detail = "VLESS", latencyText = "42 ms",
                        selected = true, enabled = true, rank = null, note = null,
                        onSelect = {}, onTestLatency = {}, onEdit = {},
                    )
                    ServerRow(
                        flag = "🇳🇱", name = "Amsterdam Beta", detail = "VLESS", latencyText = "88 ms",
                        selected = false, enabled = true, rank = null, note = null,
                        onSelect = {}, onTestLatency = {}, onEdit = {},
                    )
                }
            }
        }
        rule.onNode(hasText("Frankfurt Alpha")).assertIsSelected()
        rule.onNode(hasText("Amsterdam Beta")).assertIsNotSelected()
        // The pill names what it is and what tapping does.
        rule.onNodeWithContentDescription("Latency 42 ms, tap to re-test").assertExists()
    }
}
