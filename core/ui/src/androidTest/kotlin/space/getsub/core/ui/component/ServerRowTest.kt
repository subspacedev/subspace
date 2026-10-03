// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

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
        rule.setContent {
            SubspaceTheme(dynamicColor = false) {
                ServerRow(
                    flag = "🇫🇮", name = "Helsinki Edge", detail = "TROJAN · WS · TLS", latencyText = "—",
                    selected = false, enabled = false, rank = null, note = "can't connect with this build",
                    onSelect = {}, onTestLatency = {}, onEdit = {},
                )
            }
        }
        rule.onNodeWithText("can't connect with this build").assertExists()
        rule.onNodeWithContentDescription("Edit Helsinki Edge").assertExists()
    }
}
