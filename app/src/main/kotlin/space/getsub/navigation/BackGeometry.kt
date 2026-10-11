// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.navigation

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import kotlin.math.abs
import kotlin.math.sign

/*
 * The geometry of Android's own cross-activity back animation, so in-app Back looks like Back in
 * the Settings app. Every number here is AOSP's (WindowManager Shell's CrossActivityBackAnimation
 * and DefaultCrossActivityBackAnimation, BackProgressAnimator, BackGestureInterpolator), recorded
 * with sources in docs/agent/research/2026-10-11-system-back-animation-spec.md. Pure functions of
 * pixels and progress; this file is the gesture, BackCommitGeometry.kt the release, and
 * NavTransitions.kt turns both into graphics layers.
 */

/** `MAX_SCALE`: the size both screens shrink to during the gesture. */
internal const val BACK_MAX_SCALE = 0.9f

/** `cross_task_back_vertical_margin`: the gap the closing screen keeps from the window edges. */
internal const val BACK_MARGIN_DP = 8f

/** `cross_activity_back_entering_start_offset`, also the activity open/close slide distance. */
internal const val BACK_ENTERING_OFFSET_DP = 96f

/** `BACK_GESTURE` = PathInterpolator(0.1, 0.1, 0, 1). */
@Suppress("MagicNumber")
private val BackGestureEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/** The platform's progress (already spring-smoothed) through the BACK_GESTURE interpolator. */
internal fun backGestureInterpolation(progress: Float): Float = BackGestureEasing.transform(progress.coerceIn(0f, 1f))

internal fun Size.toRect(): Rect = Rect(0f, 0f, width, height)

internal fun Rect.scaledAboutCentre(scale: Float): Rect {
    val halfWidth = width * scale / 2f
    val halfHeight = height * scale / 2f
    return Rect(center.x - halfWidth, center.y - halfHeight, center.x + halfWidth, center.y + halfHeight)
}

/**
 * The closing (current) screen at interpolated progress [p]: the window lerped toward itself at
 * [BACK_MAX_SCALE], pushed right so its right edge sits [marginPx] inside the window, unless the
 * swipe started at the right edge, in which case it stays centred.
 */
internal fun closingRect(
    p: Float,
    fromRightEdge: Boolean,
    size: Size,
    marginPx: Float,
): Rect {
    val start = size.toRect()
    val scaled = start.scaledAboutCentre(BACK_MAX_SCALE)
    val target = if (fromRightEdge) scaled else scaled.translate(size.width - marginPx - scaled.right, 0f)
    return lerp(start, target, p)
}

/** The entering (beneath) screen: the window shifted [offsetPx] left, lerped toward itself at 0.9. */
internal fun enteringRect(
    p: Float,
    size: Size,
    offsetPx: Float,
): Rect {
    val start = size.toRect().translate(-offsetPx, 0f)
    return lerp(start, start.scaledAboutCentre(BACK_MAX_SCALE), p)
}

/**
 * How far both screens follow the finger vertically: DecelerateInterpolator of the drag, as a
 * share of half the window, times the gap the shrink has opened above the closing screen less
 * [marginPx]. [rawDy] is the touch's travel since the gesture started.
 */
internal fun verticalShift(
    rawDy: Float,
    size: Size,
    closingHeight: Float,
    marginPx: Float,
): Float {
    val halfHeight = size.height / 2f
    if (halfHeight <= 0f) return 0f
    val ratio = minOf(halfHeight, abs(rawDy)) / halfHeight
    val eased = 1f - (1f - ratio) * (1f - ratio)
    val limit = maxOf(0f, (size.height - closingHeight) / 2f - marginPx)
    return limit * eased * sign(rawDy)
}

/**
 * The `<extend>` of activity_open_enter and its siblings: a screen sliding by up to
 * [extensionPx] paints its background that far past its left and right edges, so the gap it
 * vacates, or has not reached yet, never shows the screen behind it.
 */
internal fun slideExtensionRects(
    size: Size,
    extensionPx: Float,
): Pair<Rect, Rect> =
    Rect(-extensionPx, 0f, 0f, size.height) to Rect(size.width, 0f, size.width + extensionPx, size.height)
