// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.model.DnsState
import space.getsub.core.model.ProfileDns
import space.getsub.core.model.RouteOutcome
import space.getsub.core.model.RuleSetAssetFailure
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant

/** M8.6 Part 4 Task 5: the import review sheet's content, reviewing and failed, in all four themes. */
class ImportReviewSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 1800))

    private val reviewing =
        ImportReviewState(
            stage = Stage.Reviewing,
            name = "RussiaInside",
            replacesExisting = true,
            bucketCounts = mapOf(RouteOutcome.BLOCK to 3, RouteOutcome.PROXY to 2, RouteOutcome.DIRECT to 1),
            defaultRouteIsDirect = true,
            geoDownloads =
            listOf(
                GeoDownloadPreview("example.test", "geoip.dat", approximateBytes = 21_000_000, alreadyOnDevice = false),
                GeoDownloadPreview("example.test", "geosite.dat", approximateBytes = null, alreadyOnDevice = true),
            ),
            dns = ProfileDns(fakeDns = true),
            dnsState = DnsState.Applied,
            willActivate = true,
        )

    private fun shootSheet(
        name: String,
        state: ImportReviewState,
    ) {
        ThemeVariant.entries.forEach { v ->
            paparazzi.snapshot(name = "$name-${v.fileSuffix}") {
                SubspacePreviewTheme(v) {
                    ImportReviewSheetContent(
                        state = state,
                        actions = ImportReviewActions(onConfirm = {}, onDismiss = {}),
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            // The drag handle's own height, which the real sheet supplies.
                            .padding(top = 22.dp),
                    )
                }
            }
        }
    }

    @Test
    fun importReview() = shootSheet("routing-import-review", reviewing)

    @Test
    fun importFailed() =
        shootSheet(
            "routing-import-failed",
            reviewing.copy(stage = Stage.Failed, failure = RuleSetAssetFailure.TimedOut),
        )
}
