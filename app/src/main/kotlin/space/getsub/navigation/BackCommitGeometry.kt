// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.navigation

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/*
 * What happens after a predictive back gesture commits, with AOSP's numbers: see BackGeometry.kt
 * and docs/agent/research/2026-10-11-system-back-animation-spec.md.
 */

/** Length of the post-commit animation and of the default activity open/close transitions. */
internal const val BACK_COMMIT_MILLIS = 450

/** `SPRING_SCALE`: the fling-bounce spring rests at 100, and the screens are scaled by value/100. */
internal const val FLING_SPRING_SCALE = 100f

private const val LIGHT_SCRIM_ALPHA = 0.2f
private const val DARK_SCRIM_ALPHA = 0.8f
private const val CLOSING_FADE_SPEED = 5f
private const val MAX_FLING_VELOCITY = 1000f
private const val MIN_FLING_VELOCITY = 120f
private const val MIN_FLING_PROGRESS = 0.1f
private const val EDGE_SWIPE_FLING_FACTOR = 2f

/** After commit the entering screen returns to the full window. */
internal fun postCommitEnteringTarget(size: Size): Rect = size.toRect()

/** After commit the closing screen grows back to full size while it moves [offsetPx] further right. */
internal fun postCommitClosingTarget(
    closingStart: Rect,
    size: Size,
    offsetPx: Float,
): Rect = size.toRect().translate(closingStart.left + offsetPx, 0f)

/** The closing screen fades out in the first fifth of the commit: max(1 - 5t, 0), t linear. */
internal fun postCommitClosingAlpha(t: Float): Float = maxOf(1f - t * CLOSING_FADE_SPEED, 0f)

/** The black scrim between the screens: 0.2 over a light theme, 0.8 over a dark one. */
internal fun maxScrimAlpha(darkTheme: Boolean): Float = if (darkTheme) DARK_SCRIM_ALPHA else LIGHT_SCRIM_ALPHA

/** The scrim fades out linearly over the commit. */
internal fun postCommitScrimAlpha(
    maxScrim: Float,
    t: Float,
): Float = maxScrim * (1f - t.coerceIn(0f, 1f))

/**
 * The fling-bounce spring's start velocity: the release's progress velocity (progress per second)
 * scaled by SPRING_SCALE x (1 - MAX_SCALE), doubled for an edge swipe, clamped to [0, 1000] and
 * at least 120 when the gesture committed below 0.1 progress; negative, so the spring dips first.
 */
internal fun flingStartVelocity(
    progressVelocity: Float,
    edgeSwipe: Boolean,
    gestureProgress: Float,
): Float {
    val factor = if (edgeSwipe) EDGE_SWIPE_FLING_FACTOR else 1f
    var velocity =
        (progressVelocity * FLING_SPRING_SCALE * (1f - BACK_MAX_SCALE) * factor).coerceIn(0f, MAX_FLING_VELOCITY)
    if (gestureProgress < MIN_FLING_PROGRESS) velocity = maxOf(velocity, MIN_FLING_VELOCITY)
    return -velocity
}

/** FLING_BOUNCE: both screens are scaled about their centres by min(spring / 100, 1). */
internal fun flingBounceScale(springValue: Float): Float = minOf(springValue / FLING_SPRING_SCALE, 1f)

/** [rect] scaled by [flingBounceScale] of [springValue] about its own centre. */
internal fun flingBounced(
    rect: Rect,
    springValue: Float,
): Rect = rect.scaledAboutCentre(flingBounceScale(springValue))
