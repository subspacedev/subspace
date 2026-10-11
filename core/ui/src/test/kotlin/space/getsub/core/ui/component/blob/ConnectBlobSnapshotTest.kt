// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component.blob

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.Health
import space.getsub.core.model.StartupStage
import space.getsub.core.model.failure
import space.getsub.core.ui.snapshot.snapshotThemes
import space.getsub.core.ui.snapshot.snapshotWithMotion

/**
 * One golden per M8.6 spec §3.3 row, in every theme. snapshotThemes reduces
 * motion, so these are the static shapes M8.6 spec §2.6 requires, and the
 * greyscale check of M8.6 spec §3.2 is run against them.
 *
 * layoutlib renders RenderEffect, so these show the real blur Android 12+
 * draws; the `-layered` goldens force the API 26-30 fallback.
 */
class ConnectBlobSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi()

    private fun blob(
        name: String,
        state: ConnectionState,
        actionLabel: String? = null,
    ) = paparazzi.snapshotThemes("blob-$name") {
        Clear { ConnectBlob(state, onClick = {}, contentDescription = name, actionLabel = actionLabel) }
    }

    /** The clearance ConnectBlob's KDoc asks for, so the glow is not cut by the image edge. */
    @Composable
    private fun Clear(content: @Composable () -> Unit) =
        Box(Modifier.padding(ConnectBlobDefaults.GlowClearance)) { content() }

    private fun connected(health: Health) =
        ConnectionState.Connected(sinceEpochMillis = 0L, socksPort = 1080, health = health)

    private fun reconnecting(blocked: Boolean) =
        ConnectionState.Reconnecting(reason = FailureReason.CoreStartFailed, attempt = 1, blocked = blocked)

    @Test
    fun disconnected() = blob("disconnected", ConnectionState.Disconnected)

    @Test
    fun connecting() = blob("connecting", ConnectionState.Connecting(StartupStage.StartingCore))

    /**
     * Connecting with motion on, first frame: the shape the cycle starts from.
     * It must be Connecting's own shape (SoftBurst), never Disconnecting's
     * Circle, which shares the loader centre (ARCHITECTURE.md §5.5).
     */
    @Test
    fun connectingWithMotion() =
        paparazzi.snapshotWithMotion("blob-connecting") {
            Clear {
                ConnectBlob(
                    ConnectionState.Connecting(StartupStage.StartingCore),
                    onClick = {},
                    contentDescription = "connecting",
                )
            }
        }

    @Test
    fun connectedOpen() = blob("connected-open", connected(Health.Open))

    @Test
    fun connectedIdle() = blob("connected-idle", connected(Health.Idle))

    @Test
    fun connectedStalled() = blob("connected-stalled", connected(Health.Stalled))

    @Test
    fun reconnecting() = blob("reconnecting", reconnecting(blocked = false))

    @Test
    fun blocked() = blob("blocked", reconnecting(blocked = true))

    @Test
    fun disconnecting() = blob("disconnecting", ConnectionState.Disconnecting)

    @Test
    fun failed() = blob("failed", failure(FailureReason.CoreStartFailed, ""))

    /** The action word inside the blob, under the icon (owner request, 2026-10-04). */
    @Test
    fun disconnectedWithAction() = blob("disconnected-action", ConnectionState.Disconnected, actionLabel = "Connect")

    @Test
    fun connectedWithAction() = blob("connected-action", connected(Health.Open), actionLabel = "Disconnect")

    /** Reconnecting's only exit, with the loader above the word. */
    @Test
    fun reconnectingWithAction() =
        blob("reconnecting-action", reconnecting(blocked = false), actionLabel = "Disconnect")

    /** A long translation wraps to two centred lines inside the shape, then ellipsizes. */
    @Test
    fun longActionLabel() =
        blob("long-action", connected(Health.Open), actionLabel = "Verbindung trennen und Tunnel schließen")

    /** The same inside Sunny, the off-state shape, whose valleys cut deepest into the corners. */
    @Test
    fun longConnectLabel() =
        blob("long-connect", ConnectionState.Disconnected, actionLabel = "Verbindung herstellen und schützen")

    /** The API 26-30 fallback (GlowTechnique.Layered), which no device in CI would draw. */
    @Test
    fun disconnectedLayered() = layered("disconnected", ConnectionState.Disconnected)

    @Test
    fun connectedLayered() = layered("connected-open", connected(Health.Open))

    private fun layered(
        name: String,
        state: ConnectionState,
    ) = paparazzi.snapshotThemes("blob-$name-layered") {
        CompositionLocalProvider(LocalGlowTechniqueOverride provides GlowTechnique.Layered) {
            Clear { ConnectBlob(state, onClick = {}, contentDescription = name) }
        }
    }
}
