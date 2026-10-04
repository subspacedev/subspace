// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component.blob

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
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
            BlobVisual(
                BlobShape.Sunny,
                BlobFill.Disconnected,
                BlobCenter.Power,
                BlobMotion.SlowDrift,
                BlobGlow.Tint(GlowRole.LightPrimary),
            )
        blobVisualFor(connecting) shouldBe
            BlobVisual(BlobShape.Morphing, BlobFill.Busy, BlobCenter.Loader, BlobMotion.FastDrift, BlobGlow.None)
        blobVisualFor(open) shouldBe
            BlobVisual(
                BlobShape.Cookie9,
                BlobFill.Connected,
                BlobCenter.Shield,
                BlobMotion.MediumDrift,
                BlobGlow.Breathing(GlowRole.Tertiary),
            )
        blobVisualFor(idle) shouldBe blobVisualFor(open)
        blobVisualFor(stalled) shouldBe
            BlobVisual(
                BlobShape.Cookie9,
                BlobFill.Stalled,
                BlobCenter.ShieldWarning,
                BlobMotion.Still,
                BlobGlow.DimStill(GlowRole.TertiaryContainer),
            )
        blobVisualFor(reconnecting) shouldBe
            BlobVisual(BlobShape.Pill, BlobFill.Busy, BlobCenter.Loader, BlobMotion.Still, BlobGlow.None)
        blobVisualFor(blocked) shouldBe
            BlobVisual(BlobShape.Square, BlobFill.Blocked, BlobCenter.VpnLock, BlobMotion.Still, BlobGlow.None)
        blobVisualFor(disconnecting) shouldBe
            BlobVisual(BlobShape.Circle, BlobFill.Idle, BlobCenter.Loader, BlobMotion.Still, BlobGlow.None)
        blobVisualFor(failed) shouldBe
            BlobVisual(BlobShape.Slanted, BlobFill.Failed, BlobCenter.Warning, BlobMotion.Still, BlobGlow.None)
    }

    /**
     * The owner's 2026-10-04 change: Disconnected is filled and Sunny, while
     * Disconnecting keeps Circle, so the two "off" states now differ by shape
     * as well as centre (ARCHITECTURE.md §5.5).
     */
    @Test
    fun disconnectedIsSunnyAndDisconnectingIsCircle() {
        blobVisualFor(disconnected).shape.restingPolygon() shouldBe BlobPolygon.Sunny
        blobVisualFor(disconnecting).shape.restingPolygon() shouldBe BlobPolygon.Circle
    }

    /** Only a settled tunnel and the resting Disconnected blob glow; every in-between state does not. */
    @Test
    fun onlyConnectedStalledAndDisconnectedGlow() {
        listOf(connecting, reconnecting, blocked, disconnecting, failed).forEach {
            withClue("$it") { blobVisualFor(it).glow shouldBe BlobGlow.None }
        }
        listOf(disconnected, open, idle, stalled).forEach {
            withClue("$it") { (blobVisualFor(it).glow == BlobGlow.None) shouldBe false }
        }
    }

    /** M8.6 spec §2.6: the breath is decorative, so reduced motion holds it still at a visible strength. */
    @Test
    fun glowLevelUnderReducedMotion() {
        BlobGlow.None.stillLevel(reduced = true) shouldBe 0f
        BlobGlow.None.stillLevel(reduced = false) shouldBe 0f
        BlobGlow.DimStill(GlowRole.TertiaryContainer).stillLevel(reduced = false) shouldBe GLOW_LEVEL_DIM
        BlobGlow.DimStill(GlowRole.TertiaryContainer).stillLevel(reduced = true) shouldBe GLOW_LEVEL_DIM
        BlobGlow.Tint(GlowRole.LightPrimary).stillLevel(reduced = false) shouldBe GLOW_LEVEL_TINT
        BlobGlow.Tint(GlowRole.LightPrimary).stillLevel(reduced = true) shouldBe GLOW_LEVEL_TINT
        BlobGlow.Breathing(GlowRole.Tertiary).stillLevel(reduced = true) shouldBe GLOW_LEVEL_STEADY
        // Animated, so there is no single still level.
        BlobGlow.Breathing(GlowRole.Tertiary).stillLevel(reduced = false) shouldBe null
    }

    /**
     * The off-state tint only ever lightens: it resolves to whichever of
     * primary and primaryContainer is lighter (tone 90 in a light scheme,
     * tone 80 in a dark one), never the dark light-theme primary that read
     * as a brown drop-shadow on the owner's device.
     */
    @Test
    fun lightPrimaryIsTheLighterPrimaryRole() {
        val light = lightColorScheme()
        val dark = darkColorScheme()
        GlowRole.LightPrimary.color(light) shouldBe light.primaryContainer
        GlowRole.LightPrimary.color(dark) shouldBe dark.primary
    }

    /** Every glow stays within the clearance callers leave, and the canvas holds its faint tail. */
    @Test
    fun everyGlowFitsTheClearanceAndTheCanvas() {
        listOf(disconnected, open, stalled).map { blobVisualFor(it).glow.style() }.forEach { style ->
            withClue("$style") {
                style.visibleSpread().value shouldBeLessThanOrEqualTo GLOW_CLEARANCE.value
                GLOW_CANVAS_MARGIN.value shouldBeGreaterThanOrEqualTo style.tail().value
            }
        }
        blobVisualFor(open).glow.style().visibleSpread().value shouldBeGreaterThanOrEqualTo 38f
    }

    /** A real blur from Android 12 (RenderEffect); the layered fallback below it. */
    @Test
    fun glowTechniqueByApiLevel() {
        glowTechnique(sdkInt = 26) shouldBe GlowTechnique.Layered
        glowTechnique(sdkInt = 30) shouldBe GlowTechnique.Layered
        glowTechnique(sdkInt = 31) shouldBe GlowTechnique.Blur
        glowTechnique(sdkInt = 37) shouldBe GlowTechnique.Blur
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

    private val distinctStates =
        listOf(disconnected, connecting, open, stalled, reconnecting, blocked, disconnecting, failed)

    /**
     * ARCHITECTURE.md §5.5 with motion in play: the shape-and-centre check above
     * compares resting shapes, but Connecting cycles through keyframes when
     * motion is allowed. In either motion mode, two states with the same centre
     * icon must never show a common polygon — Connecting's cycle once rested on
     * and passed through Circle with the loader, which is exactly Disconnecting.
     */
    @Test
    fun statesSharingACentreNeverShowTheSamePolygon() {
        val pairs =
            distinctStates.flatMap { a -> distinctStates.filter { it !== a }.map { b -> a to b } }
                .filter { (a, b) -> blobVisualFor(a).center == blobVisualFor(b).center }
        listOf(true, false).forEach { reduced ->
            pairs.forEach { (a, b) ->
                val common =
                    blobVisualFor(a).shape.displayablePolygons(reduced) intersect
                        blobVisualFor(b).shape.displayablePolygons(reduced)
                withClue("$a vs $b, reduced=$reduced") { common.shouldBeEmpty() }
            }
        }
    }

    /** The Loader-centred trio named in the review, spelled out. */
    @Test
    fun loaderStatesHaveDisjointShapes() {
        val loaderStates = distinctStates.filter { blobVisualFor(it).center == BlobCenter.Loader }
        loaderStates.size shouldBe 3
        listOf(true, false).forEach { reduced ->
            loaderStates
                .flatMap { blobVisualFor(it).shape.displayablePolygons(reduced) }
                .shouldBeUnique()
        }
    }

    /** The cycle starts where Morphing rests, so starting it is not a jump. */
    @Test
    fun connectingCycleStartsFromItsRestingShape() {
        ConnectingCycle.first() shouldBe BlobShape.Morphing.restingPolygon()
        BlobShape.Morphing.displayablePolygons(reduced = true) shouldBe setOf(BlobPolygon.SoftBurst)
    }

    /** No other state rests on a Connecting keyframe, even one with another centre. */
    @Test
    fun connectingCycleUsesShapesNoOtherStateRestsOn() {
        val others = BlobShape.entries.filter { it != BlobShape.Morphing }.map { it.restingPolygon() }
        (ConnectingCycle.toSet() intersect others.toSet()).shouldBeEmpty()
    }
}
