// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.log

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant

/** M8.6 spec §7.1: the session log viewer in three states, in all four themes. */
class LogViewerSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private val actions = LogViewerActions(onClear = {}, onShare = {}, onBack = {})

    private val lines: List<String> =
        List(30) { i ->
            val sec = (i % 60).toString().padStart(2, '0')
            when (i) {
                7 ->
                    "10-04 12:04:$sec.233  4321  4400 W xray    : dial tcp example.invalid:443 failed after " +
                        "retry, falling back to the next outbound in the balancer group " +
                        "(attempt 3 of 5, last error: i/o timeout)"
                19 ->
                    "10-04 12:04:$sec.871  4321  4402 E xray    : transport/internet/reality: " +
                        "handshake with example.invalid:443 rejected by the peer, " +
                        "check the short id and the public key of this profile"
                else -> "10-04 12:04:$sec.233  4321  4400 I xray    : accepted tcp:example.invalid:443"
            }
        }

    private fun shoot(
        name: String,
        state: LogViewerState,
    ) {
        ThemeVariant.entries.forEach { v ->
            paparazzi.snapshot(name = "$name-${v.fileSuffix}") {
                SubspacePreviewTheme(v) { LogViewerScreenContent(state, actions) }
            }
        }
    }

    @Test
    fun lines() = shoot("log-lines", LogViewerState(lines = lines, loading = false))

    @Test
    fun lightweight() = shoot("log-lightweight", LogViewerState(lines = lines, loading = false, lightweightMode = true))

    @Test
    fun empty() = shoot("log-empty", LogViewerState(lines = emptyList(), loading = false))
}
