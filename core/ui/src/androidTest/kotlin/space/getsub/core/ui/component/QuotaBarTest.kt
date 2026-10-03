// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.theme.SubspaceTheme

// camelCase throughout — same DEX-040 constraint GroupCardTest.kt documents
// in full.
class QuotaBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aBoundedQuotaRendersTheBarAndItsLabel() {
        composeRule.setContent {
            SubspaceTheme {
                QuotaBar(usedBytes = 1_073_741_824L, totalBytes = 5_368_709_120L)
            }
        }
        // 1_073_741_824 bytes = 1.0 GB, 5_368_709_120 bytes = 5.0 GB.
        composeRule.onNodeWithText("1.0 GB of 5.0 GB used").assertExists()
    }

    @Test
    fun totalZeroMeansUnlimitedAndRendersNothing() {
        // §A.1: total=0 is the documented "no cap" convention, not zero bytes
        // remaining — a bar reading 100% used on an unlimited plan is worse
        // than no bar.
        composeRule.setContent {
            SubspaceTheme {
                QuotaBar(usedBytes = 5L, totalBytes = 0L)
            }
        }
        composeRule.onNodeWithText("used", substring = true).assertDoesNotExist()
    }

    @Test
    fun aMissingTotalRendersNothing() {
        // "the provider did not say" must not fall back to any drawn bar.
        composeRule.setContent {
            SubspaceTheme {
                QuotaBar(usedBytes = 5L, totalBytes = null)
            }
        }
        composeRule.onNodeWithText("used", substring = true).assertDoesNotExist()
    }

    /**
     * The subscription slots now live in the card's `header`, the way the Servers
     * screen fills them; these tests build the same header around the new GroupCard.
     */
    @Composable
    private fun Card(
        name: String = "Local configs",
        header: @Composable ColumnScope.() -> Unit,
    ) = GroupCard(
        title = name,
        subtitle = "1 server",
        expanded = false,
        onToggle = {},
        menuItems = emptyList(),
        header = header,
    ) {}

    @Test
    fun groupCardWithNoQuotaBytesDrawsNoSubscriptionAffordances() {
        // A MANUAL group passes null quota bytes — the header must draw nothing extra.
        composeRule.setContent {
            SubspaceTheme {
                Card { QuotaBar(usedBytes = null, totalBytes = null) }
            }
        }
        composeRule.onNodeWithText("used", substring = true).assertDoesNotExist()
    }

    @Test
    fun groupCardWithAMeasurableQuotaRendersTheBar() {
        composeRule.setContent {
            SubspaceTheme {
                Card(name = "My subscription") { QuotaBar(usedBytes = 1_073_741_824L, totalBytes = 5_368_709_120L) }
            }
        }
        composeRule.onNodeWithText("1.0 GB of 5.0 GB used").assertExists()
    }

    @Test
    fun theFractionOverloadShowsItsLabelAndToleratesAnOverrun() {
        composeRule.setContent {
            SubspaceTheme {
                QuotaBar(fraction = 1.3f, label = "65 GB of 50 GB used")
            }
        }
        composeRule.onNodeWithText("65 GB of 50 GB used").assertExists()
    }

    // onUpdate/lastFetchedAtEpochMillis coverage. Same MANUAL-shaped call as
    // groupCardWithNoQuotaBytesDrawsNoSubscriptionAffordances above, checking the other
    // two subscription slots this time.

    @Test
    fun groupCardWithNoSubscriptionContextDrawsNoUpdateAffordance() {
        composeRule.setContent {
            SubspaceTheme {
                Card {
                    GroupCardUpdateRow(groupName = "Local configs", lastFetchedAtEpochMillis = null, onUpdate = null)
                }
            }
        }
        composeRule.onNodeWithContentDescription("Update Local configs").assertDoesNotExist()
        composeRule.onNodeWithText("Updated", substring = true).assertDoesNotExist()
    }

    @Test
    fun anUpdateCallbackRendersTheButtonAndInvokesItOnTap() {
        var updated = false
        composeRule.setContent {
            SubspaceTheme {
                Card(name = "My subscription") {
                    GroupCardUpdateRow(
                        groupName = "My subscription",
                        lastFetchedAtEpochMillis = null,
                        onUpdate = { updated = true },
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("Update My subscription").performClick()
        updated shouldBe true
    }

    @Test
    fun aLastFetchedTimestampRendersAnAgeText() {
        composeRule.setContent {
            SubspaceTheme {
                Card(name = "My subscription") {
                    GroupCardUpdateRow(
                        groupName = "My subscription",
                        lastFetchedAtEpochMillis = System.currentTimeMillis() - 5_000L,
                        onUpdate = null,
                    )
                }
            }
        }
        composeRule.onNodeWithText("Updated", substring = true).assertExists()
    }
}
