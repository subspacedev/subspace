// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.StartupStage
import space.getsub.core.model.failure
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant

/** M8.6 spec §7.1, review focus 4: long text at 360 dp. Its own class: Paparazzi allows one rule per class. */
class HomeLongTextSnapshotTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 720, screenHeight = 3200, density = Density.XHIGH),
        )

    private val profile = snapshotProfile("A".repeat(50))

    @Test
    fun longText() {
        val state =
            HomeState(
                connection = failure(FailureReason.CoreStartFailed, LONG_DETAIL),
                activeProfile = profile,
                hasAnyProfile = true,
                activeRuleSetName = "R".repeat(60),
            )
        paparazzi.snapshot(name = "home-long-text-${ThemeVariant.FriendlyLight.fileSuffix}") {
            SubspacePreviewTheme(ThemeVariant.FriendlyLight) {
                HomeScreenContent(state, HomeActions({}, {}, {}, {}, {}, {}, {}, {}), nowMillis = { 0L })
            }
        }
    }

    /**
     * The longest one-line status at 360 dp: "Establishing tunnel interface… ·
     * Step 6 of 7". Only a failure's detail may wrap.
     */
    @Test
    fun longestStatusLine() {
        val state =
            HomeState(
                connection = ConnectionState.Connecting(StartupStage.EstablishingTun),
                activeProfile = profile,
                hasAnyProfile = true,
            )
        paparazzi.snapshot(name = "home-longest-status-${ThemeVariant.FriendlyLight.fileSuffix}") {
            SubspacePreviewTheme(ThemeVariant.FriendlyLight) {
                HomeScreenContent(state, HomeActions({}, {}, {}, {}, {}, {}, {}, {}), nowMillis = { 0L })
            }
        }
    }
}

// ~200 characters of plain prose: the redactor passes words separated by spaces, and
// replaces base64-, URL-, IP- and uuid-like tokens, which would turn this into a sentinel.
private const val LONG_DETAIL =
    "The tunnel core refused to start because the generated settings could not be loaded " +
        "after several attempts, and nothing was changed on this phone while it tried again just now."
