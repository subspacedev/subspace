// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component.blob

import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.Health
import space.getsub.core.model.StartupStage
import space.getsub.core.model.failure

class BlobVisualTest {
    private val disconnected = ConnectionState.Disconnected
    private val connecting = ConnectionState.Connecting(StartupStage.StartingCore)
    private val open = connected(Health.Open)
    private val idle = connected(Health.Idle)
    private val stalled = connected(Health.Stalled)
    private val reconnecting = reconnecting(blocked = false)
    private val blocked = reconnecting(blocked = true)
    private val disconnecting = ConnectionState.Disconnecting
    private val failed = failure(FailureReason.CoreStartFailed, "")

    private fun connected(health: Health) =
        ConnectionState.Connected(sinceEpochMillis = 0L, socksPort = 1080, health = health)

    private fun reconnecting(blocked: Boolean) =
        ConnectionState.Reconnecting(reason = FailureReason.CoreStartFailed, attempt = 1, blocked = blocked)

    @Test
    fun eachStateMatchesTheSpecTable() {
        blobVisualFor(disconnected) shouldBe
            BlobVisual(BlobShape.Circle, BlobFill.Outline, BlobCenter.Power, BlobMotion.SlowDrift)
        blobVisualFor(connecting) shouldBe
            BlobVisual(BlobShape.Morphing, BlobFill.Busy, BlobCenter.Loader, BlobMotion.FastDrift)
        blobVisualFor(open) shouldBe
            BlobVisual(BlobShape.Cookie9, BlobFill.Connected, BlobCenter.Shield, BlobMotion.Breathing)
        blobVisualFor(idle) shouldBe
            BlobVisual(BlobShape.Cookie9, BlobFill.Connected, BlobCenter.Shield, BlobMotion.Breathing)
        blobVisualFor(stalled) shouldBe
            BlobVisual(BlobShape.Cookie9, BlobFill.Stalled, BlobCenter.ShieldWarning, BlobMotion.StillHalo)
        blobVisualFor(reconnecting) shouldBe
            BlobVisual(BlobShape.Pill, BlobFill.Busy, BlobCenter.Loader, BlobMotion.Still)
        blobVisualFor(blocked) shouldBe
            BlobVisual(BlobShape.Square, BlobFill.Blocked, BlobCenter.VpnLock, BlobMotion.Still)
        blobVisualFor(disconnecting) shouldBe
            BlobVisual(BlobShape.Circle, BlobFill.Idle, BlobCenter.Loader, BlobMotion.Still)
        blobVisualFor(failed) shouldBe
            BlobVisual(BlobShape.Slanted, BlobFill.Failed, BlobCenter.Warning, BlobMotion.Still)
    }

    /**
     * ARCHITECTURE.md §5.5 / M8.6 spec §3.2: with colour removed (greyscale, or a
     * wallpaper that makes tertiary look like the error colour), shape and centre
     * icon alone must still tell every state apart. Open and Idle are the one
     * intended exception (both healthy, M8.5 spec §4.2); their detail line differs.
     */
    @Test
    fun noTwoStatesShareAShapeAndCentreIcon() {
        listOf(disconnected, connecting, open, stalled, reconnecting, blocked, disconnecting, failed)
            .map { blobVisualFor(it).let { v -> v.shape to v.center } }
            .shouldBeUnique()
    }
}
