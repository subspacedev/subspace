// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.navigation

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * [cardTransformAt] is the whole shape of the predictive back card: NavHost seeks the pop
 * transition linearly by gesture progress, so whatever this returns at a progress is what the
 * user sees under their finger at that progress.
 */
class CardTransformTest {
    private val tolerance = 1e-4f

    @Test
    fun `progress 0 is the screen at rest - full scale, square, unshifted, flat`() {
        val rest = cardTransformAt(0f)
        rest.scale shouldBe (1f plusOrMinus tolerance)
        rest.cornerDp shouldBe (0f plusOrMinus tolerance)
        rest.translationFraction shouldBe (0f plusOrMinus tolerance)
        rest.elevationDp shouldBe (0f plusOrMinus tolerance)
    }

    @Test
    fun `mid gesture the card is part shrunk, part rounded and barely shifted`() {
        val mid = cardTransformAt(0.25f)
        mid.scale shouldBe (0.95f plusOrMinus tolerance)
        mid.cornerDp shouldBe (14f plusOrMinus tolerance)
        mid.translationFraction shouldBe (0.025f plusOrMinus tolerance)
    }

    @Test
    fun `the shrink finishes at 0_90 scale and 28 dp corners and holds there`() {
        listOf(0.5f, 0.75f, 1f).forEach { progress ->
            val card = cardTransformAt(progress)
            card.scale shouldBe (0.9f plusOrMinus tolerance)
            card.cornerDp shouldBe (28f plusOrMinus tolerance)
        }
    }

    @Test
    fun `at the end the card is entirely off screen`() {
        onScreenFraction(cardTransformAt(1f)) shouldBe (0f plusOrMinus tolerance)
    }

    @Test
    fun `through moderate progress the card stays mostly on screen, not half slid out`() {
        steps(upTo = 0.6f).forEach { progress ->
            onScreenFraction(cardTransformAt(progress)) shouldBeGreaterThanOrEqual 0.9f
        }
    }

    @Test
    fun `out-of-range progress is clamped`() {
        cardTransformAt(-0.5f) shouldBe cardTransformAt(0f)
        cardTransformAt(1.5f) shouldBe cardTransformAt(1f)
    }

    @Test
    fun `every component moves one way only as progress grows`() {
        steps(upTo = 1f).zipWithNext().forEach { (a, b) ->
            val before = cardTransformAt(a)
            val after = cardTransformAt(b)
            after.scale shouldBeLessThanOrEqual before.scale
            after.cornerDp shouldBeGreaterThanOrEqual before.cornerDp
            after.translationFraction shouldBeGreaterThanOrEqual before.translationFraction
            after.elevationDp shouldBeGreaterThanOrEqual before.elevationDp
        }
    }

    private fun steps(upTo: Float): List<Float> = (0..STEPS).map { it * upTo / STEPS }

    /**
     * The share of the card's own width inside a container of width 1: the card is scaled
     * about its centre, then shifted by `translationFraction` of the container.
     */
    private fun onScreenFraction(card: CardTransform): Float {
        val half = card.scale / 2f
        val left = 0.5f - half + card.translationFraction
        val right = 0.5f + half + card.translationFraction
        val visible = (minOf(right, 1f) - maxOf(left, 0f)).coerceAtLeast(0f)
        return visible / card.scale
    }

    private companion object {
        const val STEPS = 100
    }
}
