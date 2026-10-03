// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component.blob

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
 */
class ConnectBlobSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi()

    private fun blob(
        name: String,
        state: ConnectionState,
    ) = paparazzi.snapshotThemes("blob-$name") {
        ConnectBlob(state, onClick = {}, contentDescription = name)
    }

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
            ConnectBlob(
                ConnectionState.Connecting(StartupStage.StartingCore),
                onClick = {},
                contentDescription = "connecting",
            )
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
}
