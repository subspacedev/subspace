// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component.blob

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.Health
import space.getsub.core.ui.theme.BlobTiming

enum class BlobShape { Sunny, Circle, Morphing, Cookie9, Pill, Square, Slanted }

/**
 * The fill role, resolved against `MaterialTheme.colorScheme` by ConnectBlob
 * (M8.6 spec §3.3, amended 2026-10-04): a softened primaryContainer
 * ([softPrimaryContainer]),
 * surfaceContainerHighest, secondaryContainer, tertiary, tertiaryContainer,
 * errorContainer, errorContainer.
 */
enum class BlobFill { Disconnected, Idle, Busy, Connected, Stalled, Blocked, Failed }

enum class BlobCenter { Power, Loader, Shield, ShieldWarning, VpnLock, Warning }

/** Drift only: one turn per 14 s, 11 s or 5 s (BlobTiming), or none. */
enum class BlobMotion { SlowDrift, MediumDrift, FastDrift, Still }

/**
 * The colour role a glow is drawn in, resolved against `MaterialTheme.colorScheme`.
 * [DisconnectedFill] is the off-state's own softened fill, so its tint and the
 * blob match.
 */
enum class GlowRole { DisconnectedFill, Tertiary, TertiaryContainer }

/**
 * The blurred, shape-following light behind the blob (M8.6 spec §3.3, amended
 * 2026-10-04). [Tint] is a very faint, wide, soft wash; [DimStill] holds one
 * faint level; [Breathing] breathes over BlobTiming.HALO_BREATH_MILLIS, and
 * holds still under reduced motion.
 */
sealed interface BlobGlow {
    data object None : BlobGlow

    data class Tint(val role: GlowRole) : BlobGlow

    data class DimStill(val role: GlowRole) : BlobGlow

    data class Breathing(val role: GlowRole) : BlobGlow
}

/** How the blob draws one connection state (M8.6 spec §3.3). */
data class BlobVisual(
    val shape: BlobShape,
    val fill: BlobFill,
    val center: BlobCenter,
    val motion: BlobMotion,
    val glow: BlobGlow,
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
            BlobVisual(
                // The off-state shape: Sunny, a one-line swap (to Circle, say).
                BlobShape.Sunny,
                BlobFill.Disconnected,
                BlobCenter.Power,
                BlobMotion.SlowDrift,
                BlobGlow.Tint(GlowRole.DisconnectedFill),
            )
        is ConnectionState.Connecting ->
            BlobVisual(BlobShape.Morphing, BlobFill.Busy, BlobCenter.Loader, BlobMotion.FastDrift, BlobGlow.None)
        is ConnectionState.Connected ->
            when (state.health) {
                Health.Stalled ->
                    BlobVisual(
                        BlobShape.Cookie9,
                        BlobFill.Stalled,
                        BlobCenter.ShieldWarning,
                        BlobMotion.Still,
                        BlobGlow.DimStill(GlowRole.TertiaryContainer),
                    )
                Health.Open, Health.Idle ->
                    BlobVisual(
                        BlobShape.Cookie9,
                        BlobFill.Connected,
                        BlobCenter.Shield,
                        BlobMotion.MediumDrift,
                        BlobGlow.Breathing(GlowRole.Tertiary),
                    )
            }
        is ConnectionState.Reconnecting ->
            if (state.blocked) {
                BlobVisual(BlobShape.Square, BlobFill.Blocked, BlobCenter.VpnLock, BlobMotion.Still, BlobGlow.None)
            } else {
                BlobVisual(BlobShape.Pill, BlobFill.Busy, BlobCenter.Loader, BlobMotion.Still, BlobGlow.None)
            }
        ConnectionState.Disconnecting ->
            BlobVisual(BlobShape.Circle, BlobFill.Idle, BlobCenter.Loader, BlobMotion.Still, BlobGlow.None)
        is ConnectionState.Failed ->
            BlobVisual(BlobShape.Slanted, BlobFill.Failed, BlobCenter.Warning, BlobMotion.Still, BlobGlow.None)
    }

/**
 * The MaterialShapes members the blob draws, by name, so the colour-free rule
 * can be tested without Compose. ConnectBlob maps each to its RoundedPolygon.
 */
internal enum class BlobPolygon { Sunny, Circle, SoftBurst, Cookie6Sided, Cookie9Sided, Pill, Square, Slanted }

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
        BlobShape.Sunny -> BlobPolygon.Sunny
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

/** The fill's container colour (M8.6 spec §3.3). */
internal fun BlobFill.container(scheme: ColorScheme): Color =
    when (this) {
        BlobFill.Disconnected -> scheme.softPrimaryContainer()
        BlobFill.Idle -> scheme.surfaceContainerHighest
        BlobFill.Busy -> scheme.secondaryContainer
        BlobFill.Connected -> scheme.tertiary
        BlobFill.Stalled -> scheme.tertiaryContainer
        BlobFill.Blocked, BlobFill.Failed -> scheme.errorContainer
    }

/** The matching on-colour, for the centre icon and loader. */
internal fun BlobFill.content(scheme: ColorScheme): Color =
    when (this) {
        BlobFill.Disconnected -> scheme.onPrimaryContainer
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
        BlobMotion.MediumDrift -> BlobTiming.CONNECTED_DRIFT_MILLIS
        BlobMotion.FastDrift -> BlobTiming.CONNECTING_DRIFT_MILLIS
        BlobMotion.Still -> null
    }

/** The glow's colour role, or null when the state has none. */
internal val BlobGlow.role: GlowRole?
    get() =
        when (this) {
            BlobGlow.None -> null
            is BlobGlow.Tint -> role
            is BlobGlow.DimStill -> role
            is BlobGlow.Breathing -> role
        }

internal fun GlowRole.color(scheme: ColorScheme): Color =
    when (this) {
        GlowRole.DisconnectedFill -> scheme.softPrimaryContainer()
        GlowRole.Tertiary -> scheme.tertiary
        GlowRole.TertiaryContainer -> scheme.tertiaryContainer
    }

/**
 * How far the off-state fill sits from surfaceContainerHigh towards
 * primaryContainer. Full primaryContainer read too vivid against the page,
 * as if already active (owner, 2026-10-04).
 */
internal const val DISCONNECTED_FILL_FRACTION = 0.5f

/**
 * The off-state fill: primaryContainer softened towards surfaceContainerHigh.
 * Both are scheme roles, so it still follows the wallpaper; BlobVisualTest
 * holds onPrimaryContainer to 4.5:1 against it in every theme variant.
 */
internal fun ColorScheme.softPrimaryContainer(): Color =
    lerp(surfaceContainerHigh, primaryContainer, DISCONNECTED_FILL_FRACTION)
