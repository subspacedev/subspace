// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.model.BucketField
import space.getsub.core.model.DomainStrategy
import space.getsub.core.model.RouteOutcome
import space.getsub.core.model.RuleBucket
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant

/**
 * M8.6 Part 4 Task 6: the rule set editor (M8.6 spec §4.6) in all four themes — an existing rule
 * set, a new one whose empty fields are collapsed (fix S5), and an entry with an inline problem.
 * The screen is tall enough to show the whole form; the save bar floats at its bottom.
 */
class RuleSetEditorSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 5200))

    private val noActions =
        RuleSetEditorActions(
            onBack = {},
            onNameChanged = {},
            onDraftChanged = { _, _, _ -> },
            onAddEntry = { _, _, _ -> },
            onRemoveEntry = { _, _, _ -> },
            onMoveEarlier = {},
            onMoveLater = {},
            onDomainStrategyChanged = {},
            onSave = {},
        )

    private val categories =
        RuleSetEditorState(
            loading = false,
            siteCategories = listOf(GeoCategory("category-ads-all", 8421), GeoCategory("google", 1024)),
            ipCategories = listOf(GeoCategory("private", 18)),
        )

    // The design's own sample rule set (Phone.dc.html RULE_CONTENT 1).
    private val existing =
        categories.copy(
            id = 1,
            name = "Everyday",
            buckets =
            mapOf(
                RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:category-ads-all")),
                RouteOutcome.PROXY to
                    RuleBucket(
                        sites = listOf("geosite:google", "youtube.com", "full:api.openai.com"),
                        ips = listOf("geoip:telegram"),
                    ),
                RouteOutcome.DIRECT to
                    RuleBucket(sites = listOf("geosite:private"), ips = listOf("geoip:private", "192.168.0.0/16")),
            ),
            order = listOf(RouteOutcome.BLOCK, RouteOutcome.PROXY, RouteOutcome.DIRECT),
            domainStrategy = DomainStrategy.IP_IF_NON_MATCH,
        )

    private fun shoot(
        name: String,
        state: RuleSetEditorState,
    ) {
        ThemeVariant.entries.forEach { v ->
            paparazzi.snapshot(name = "$name-${v.fileSuffix}") {
                SubspacePreviewTheme(v) { RuleSetEditorContent(state = state, actions = noActions) }
            }
        }
    }

    @Test
    fun existingRuleSet() = shoot("rule-set-editor-existing", existing.copy(baseline = existing.edits))

    @Test
    fun newRuleSet() = shoot("rule-set-editor-new", categories)

    @Test
    fun inlineProblem() =
        shoot(
            "rule-set-editor-problem",
            existing.copy(
                domainStrategy = DomainStrategy.AS_IS,
                drafts = mapOf((RouteOutcome.PROXY to BucketField.SITES) to "geoip:google"),
            ),
        )
}
