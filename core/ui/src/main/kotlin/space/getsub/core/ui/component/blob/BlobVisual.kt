// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component.blob

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.Health
import space.getsub.core.ui.theme.BlobTiming

enum class BlobShape { Circle, Morphing, Cookie9, Pill, Square, Slanted }

/**
 * The fill role, resolved against `MaterialTheme.colorScheme` by ConnectBlob
 * (M8.6 spec §3.3): [Outline] is no fill and a 2 dp `outline` stroke; the rest
 * are surfaceContainerHighest, secondaryContainer, tertiary,
 * tertiaryContainer, errorContainer, errorContainer.
 */
enum class BlobFill { Outline, Idle, Busy, Connected, Stalled, Blocked, Failed }

enum class BlobCenter { Power, Loader, Shield, ShieldWarning, VpnLock, Warning }

/**
 * [Breathing] drifts with a breathing halo; [StillHalo] holds still with a
 * solid halo (M8.6 spec §3.3, Stalled's "halo still"); [Still] has no halo.
 */
enum class BlobMotion { SlowDrift, FastDrift, Breathing, StillHalo, Still }

/** How the blob draws one connection state (M8.6 spec §3.3). */
data class BlobVisual(
    val shape: BlobShape,
    val fill: BlobFill,
    val center: BlobCenter,
    val motion: BlobMotion,
)

/**
 * The M8.6 spec §3.3 table as code. Shape and centre differ for every state
 * except Open/Idle, so the blob is readable without colour (ARCHITECTURE.md §5.5,
 * M8.6 spec §3.2). BlobVisualTest holds this to the table.
 *
 * The `when` is exhaustive with no `else`: a new ConnectionState or Health
 * value must fail to compile here rather than be silently absorbed.
 */
fun blobVisualFor(state: ConnectionState): BlobVisual =
    when (state) {
        ConnectionState.Disconnected ->
            BlobVisual(BlobShape.Circle, BlobFill.Outline, BlobCenter.Power, BlobMotion.SlowDrift)
        is ConnectionState.Connecting ->
            BlobVisual(BlobShape.Morphing, BlobFill.Busy, BlobCenter.Loader, BlobMotion.FastDrift)
        is ConnectionState.Connected ->
            when (state.health) {
                Health.Stalled ->
                    BlobVisual(BlobShape.Cookie9, BlobFill.Stalled, BlobCenter.ShieldWarning, BlobMotion.StillHalo)
                Health.Open, Health.Idle ->
                    BlobVisual(BlobShape.Cookie9, BlobFill.Connected, BlobCenter.Shield, BlobMotion.Breathing)
            }
        is ConnectionState.Reconnecting ->
            if (state.blocked) {
                BlobVisual(BlobShape.Square, BlobFill.Blocked, BlobCenter.VpnLock, BlobMotion.Still)
            } else {
                BlobVisual(BlobShape.Pill, BlobFill.Busy, BlobCenter.Loader, BlobMotion.Still)
            }
        ConnectionState.Disconnecting ->
            BlobVisual(BlobShape.Circle, BlobFill.Idle, BlobCenter.Loader, BlobMotion.Still)
        is ConnectionState.Failed ->
            BlobVisual(BlobShape.Slanted, BlobFill.Failed, BlobCenter.Warning, BlobMotion.Still)
    }

/**
 * The MaterialShapes members the blob draws, by name, so the colour-free rule
 * can be tested without Compose. ConnectBlob maps each to its RoundedPolygon.
 */
internal enum class BlobPolygon { Circle, SoftBurst, Cookie6Sided, Cookie9Sided, Pill, Square, Slanted }

/**
 * The Connecting shape cycle when motion is allowed, starting from where
 * [BlobShape.Morphing] rests. Neither member is drawn by any other state, so
 * no frame of the cycle shows a Loader-centred state's shape — Circle is
 * Disconnecting's, Pill is Reconnecting's (ARCHITECTURE.md §5.5). Cookie6Sided
 * is round and soft like SoftBurst, so the cycle reads as busy rather than
 * alarming, and its six lobes keep it apart from Connected's nine-lobed cookie.
 */
internal val ConnectingCycle: List<BlobPolygon> = listOf(BlobPolygon.SoftBurst, BlobPolygon.Cookie6Sided)

/** The polygon a shape settles on, in either motion mode. */
internal fun BlobShape.restingPolygon(): BlobPolygon =
    when (this) {
        BlobShape.Circle -> BlobPolygon.Circle
        BlobShape.Morphing -> BlobPolygon.SoftBurst
        BlobShape.Cookie9 -> BlobPolygon.Cookie9Sided
        BlobShape.Pill -> BlobPolygon.Pill
        BlobShape.Square -> BlobPolygon.Square
        BlobShape.Slanted -> BlobPolygon.Slanted
    }

/**
 * Every polygon a shape can be seen resting on or passing through as a
 * keyframe while its state holds. Transitions between states are not included:
 * they morph from one state's polygon to the next and end on the new one.
 */
internal fun BlobShape.displayablePolygons(reduced: Boolean): Set<BlobPolygon> =
    if (this == BlobShape.Morphing && !reduced) ConnectingCycle.toSet() else setOf(restingPolygon())

/**
 * The fill's container colour (M8.6 spec §3.3). [BlobFill.Outline] has none;
 * ConnectBlob strokes it in `outline` instead.
 */
internal fun BlobFill.container(scheme: ColorScheme): Color =
    when (this) {
        BlobFill.Outline -> Color.Transparent
        BlobFill.Idle -> scheme.surfaceContainerHighest
        BlobFill.Busy -> scheme.secondaryContainer
        BlobFill.Connected -> scheme.tertiary
        BlobFill.Stalled -> scheme.tertiaryContainer
        BlobFill.Blocked, BlobFill.Failed -> scheme.errorContainer
    }

/** The matching on-colour, for the centre icon and loader. */
internal fun BlobFill.content(scheme: ColorScheme): Color =
    when (this) {
        BlobFill.Outline -> scheme.onSurfaceVariant
        BlobFill.Idle -> scheme.onSurface
        BlobFill.Busy -> scheme.onSecondaryContainer
        BlobFill.Connected -> scheme.onTertiary
        BlobFill.Stalled -> scheme.onTertiaryContainer
        BlobFill.Blocked, BlobFill.Failed -> scheme.onErrorContainer
    }

/** One full turn per this many millis, or null when the shape holds still. */
internal fun BlobMotion.driftMillis(): Int? =
    when (this) {
        BlobMotion.SlowDrift -> BlobTiming.IDLE_DRIFT_MILLIS
        BlobMotion.FastDrift -> BlobTiming.CONNECTING_DRIFT_MILLIS
        BlobMotion.Breathing -> BlobTiming.CONNECTED_DRIFT_MILLIS
        BlobMotion.StillHalo, BlobMotion.Still -> null
    }
