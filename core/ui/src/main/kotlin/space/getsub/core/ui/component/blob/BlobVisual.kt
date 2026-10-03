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
 * The fill role, resolved against `MaterialTheme.colorScheme` by ConnectBlob:
 * surfaceContainerHighest, secondaryContainer, tertiary, tertiaryContainer,
 * errorContainer, errorContainer (M8.6 spec §3.3).
 */
enum class BlobFill { Idle, Busy, Connected, Stalled, Blocked, Failed }

enum class BlobCenter { Power, Loader, Shield, ShieldWarning, VpnLock, Warning }

enum class BlobMotion { SlowDrift, FastDrift, Breathing, Still }

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
            BlobVisual(BlobShape.Circle, BlobFill.Idle, BlobCenter.Power, BlobMotion.SlowDrift)
        is ConnectionState.Connecting ->
            BlobVisual(BlobShape.Morphing, BlobFill.Busy, BlobCenter.Loader, BlobMotion.FastDrift)
        is ConnectionState.Connected ->
            when (state.health) {
                Health.Stalled ->
                    BlobVisual(BlobShape.Cookie9, BlobFill.Stalled, BlobCenter.ShieldWarning, BlobMotion.Still)
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
            BlobVisual(BlobShape.Circle, BlobFill.Busy, BlobCenter.Loader, BlobMotion.Still)
        is ConnectionState.Failed ->
            BlobVisual(BlobShape.Slanted, BlobFill.Failed, BlobCenter.Warning, BlobMotion.Still)
    }

/** The fill's container colour (M8.6 spec §3.3). */
internal fun BlobFill.container(scheme: ColorScheme): Color =
    when (this) {
        BlobFill.Idle -> scheme.surfaceContainerHighest
        BlobFill.Busy -> scheme.secondaryContainer
        BlobFill.Connected -> scheme.tertiary
        BlobFill.Stalled -> scheme.tertiaryContainer
        BlobFill.Blocked, BlobFill.Failed -> scheme.errorContainer
    }

/** The matching on-colour, for the centre icon and loader. */
internal fun BlobFill.content(scheme: ColorScheme): Color =
    when (this) {
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
        BlobMotion.Still -> null
    }
