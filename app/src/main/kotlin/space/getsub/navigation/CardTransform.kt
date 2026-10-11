// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.navigation

/** Where the moving card is drawn at one point of the motion: see [cardTransformAt]. */
internal data class CardTransform(
    /** Uniform scale about the card's centre. */
    val scale: Float,
    /** Corner radius of the card's clip. */
    val cornerDp: Float,
    /** Horizontal shift, as a fraction of the container's width, toward +x. */
    val translationFraction: Float,
    /** Shadow that separates the card from the screen beneath it. */
    val elevationDp: Float,
)

/** Fraction of the motion spent shrinking and rounding; the rest slides the card out. */
internal const val CARD_SHRINK_END = 0.5f
internal const val CARD_MIN_SCALE = 0.9f
internal const val CARD_MAX_CORNER_DP = 28f
internal const val CARD_MAX_ELEVATION_DP = 6f

/** How far the card drifts while it shrinks: a hint of where it is going. */
private const val PEEK_SHIFT = 0.05f

/** Far enough that a card at [CARD_MIN_SCALE] has its left edge past the right edge. */
private const val EXIT_SHIFT = 1f

/**
 * The card's shape at [progress] of the motion, 0 (at rest) to 1 (gone).
 *
 * NavHost seeks the pop transition linearly by the gesture's progress, so this is what the user
 * sees under their finger. The first [CARD_SHRINK_END] of the range scales, rounds and nudges the
 * card in proportion; only after that does it slide out, accelerating, so a card at moderate
 * progress is shrunk and slightly shifted, never half off screen. A push plays the same curve
 * backwards.
 */
internal fun cardTransformAt(progress: Float): CardTransform {
    val p = progress.coerceIn(0f, 1f)
    val shrink = (p / CARD_SHRINK_END).coerceAtMost(1f)
    val slide = ((p - CARD_SHRINK_END) / (1f - CARD_SHRINK_END)).coerceAtLeast(0f)
    return CardTransform(
        scale = 1f - (1f - CARD_MIN_SCALE) * shrink,
        cornerDp = CARD_MAX_CORNER_DP * shrink,
        translationFraction = PEEK_SHIFT * shrink + (EXIT_SHIFT - PEEK_SHIFT) * slide * slide,
        elevationDp = CARD_MAX_ELEVATION_DP * shrink,
    )
}
