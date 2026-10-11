// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.navigation

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The numbers are AOSP's cross-activity back animation, recorded with their sources in
 * `docs/agent/research/2026-10-11-system-back-animation-spec.md`.
 */
class BackGeometryTest {
    private val size = Size(1080f, 2400f)
    private val full = Rect(0f, 0f, 1080f, 2400f)
    private val margin = 21f // 8 dp at 2.625x
    private val offset = 252f // 96 dp at 2.625x
    private val tolerance = 0.01f

    private fun Rect.shouldBeRect(expected: Rect) {
        left shouldBe (expected.left plusOrMinus tolerance)
        top shouldBe (expected.top plusOrMinus tolerance)
        right shouldBe (expected.right plusOrMinus tolerance)
        bottom shouldBe (expected.bottom plusOrMinus tolerance)
    }

    @Test
    fun `at progress 0 the closing screen fills the window`() {
        closingRect(0f, fromRightEdge = false, size = size, marginPx = margin).shouldBeRect(full)
    }

    @Test
    fun `a left-edge swipe ends at 0_9 scale with the right edge 8 dp inside the window`() {
        val rect = closingRect(1f, fromRightEdge = false, size = size, marginPx = margin)
        rect.width shouldBe (size.width * 0.9f plusOrMinus tolerance)
        rect.height shouldBe (size.height * 0.9f plusOrMinus tolerance)
        rect.right shouldBe (size.width - margin plusOrMinus tolerance)
        rect.top shouldBe (size.height * 0.05f plusOrMinus tolerance)
    }

    @Test
    fun `a right-edge swipe ends at 0_9 scale, centred`() {
        val rect = closingRect(1f, fromRightEdge = true, size = size, marginPx = margin)
        rect.width shouldBe (size.width * 0.9f plusOrMinus tolerance)
        rect.center.x shouldBe (size.width / 2f plusOrMinus tolerance)
        rect.center.y shouldBe (size.height / 2f plusOrMinus tolerance)
    }

    @Test
    fun `the closing rect is a straight interpolation between start and target`() {
        val start = closingRect(0f, false, size, margin)
        val end = closingRect(1f, false, size, margin)
        closingRect(0.5f, false, size, margin).shouldBeRect(
            Rect(
                (start.left + end.left) / 2f,
                (start.top + end.top) / 2f,
                (start.right + end.right) / 2f,
                (start.bottom + end.bottom) / 2f,
            ),
        )
    }

    @Test
    fun `the entering screen starts 96 dp to the left at full size`() {
        enteringRect(0f, size, offsetPx = offset).shouldBeRect(Rect(-offset, 0f, size.width - offset, size.height))
    }

    @Test
    fun `the entering screen ends as its start rect scaled 0_9 about its own centre`() {
        val rect = enteringRect(1f, size, offsetPx = offset)
        rect.width shouldBe (size.width * 0.9f plusOrMinus tolerance)
        rect.center.x shouldBe (size.width / 2f - offset plusOrMinus tolerance)
        rect.center.y shouldBe (size.height / 2f plusOrMinus tolerance)
    }

    @Test
    fun `vertical follow is zero without vertical movement`() {
        verticalShift(rawDy = 0f, size = size, closingHeight = size.height * 0.9f, marginPx = margin) shouldBe
            (0f plusOrMinus tolerance)
    }

    @Test
    fun `vertical follow is limited to the gap left by the shrink, less the 8 dp margin`() {
        val closingHeight = size.height * 0.9f
        val limit = (size.height - closingHeight) / 2f - margin
        verticalShift(size.height, size, closingHeight, margin) shouldBe (limit plusOrMinus tolerance)
        verticalShift(-size.height, size, closingHeight, margin) shouldBe (-limit plusOrMinus tolerance)
    }

    @Test
    fun `vertical follow decelerates - a quarter-screen drag gives three quarters of the limit`() {
        val closingHeight = size.height * 0.9f
        val limit = (size.height - closingHeight) / 2f - margin
        // ratio 0.5 -> 1 - (1 - 0.5)^2 = 0.75
        verticalShift(size.height / 4f, size, closingHeight, margin) shouldBe (limit * 0.75f plusOrMinus tolerance)
    }

    @Test
    fun `vertical follow is zero while the shrink has not opened a gap wider than the margin`() {
        verticalShift(size.height, size, closingHeight = size.height, marginPx = margin) shouldBe
            (0f plusOrMinus tolerance)
    }

    @Test
    fun `post-commit targets - entering to the window, closing to the window shifted right`() {
        val closingStart = closingRect(0.6f, false, size, margin)
        postCommitEnteringTarget(size).shouldBeRect(full)
        postCommitClosingTarget(closingStart, size, offset).shouldBeRect(
            Rect(closingStart.left + offset, 0f, closingStart.left + offset + size.width, size.height),
        )
    }

    @Test
    fun `the closing screen is gone by the first fifth of the commit`() {
        postCommitClosingAlpha(0f) shouldBe (1f plusOrMinus tolerance)
        postCommitClosingAlpha(0.1f) shouldBe (0.5f plusOrMinus tolerance)
        postCommitClosingAlpha(0.2f) shouldBe (0f plusOrMinus tolerance)
        postCommitClosingAlpha(0.7f) shouldBe (0f plusOrMinus tolerance)
    }

    @Test
    fun `the scrim is 0_2 light, 0_8 dark, and fades out linearly over the commit`() {
        maxScrimAlpha(darkTheme = false) shouldBe (0.2f plusOrMinus tolerance)
        maxScrimAlpha(darkTheme = true) shouldBe (0.8f plusOrMinus tolerance)
        postCommitScrimAlpha(0.8f, 0.25f) shouldBe (0.6f plusOrMinus tolerance)
        postCommitScrimAlpha(0.8f, 1f) shouldBe (0f plusOrMinus tolerance)
    }

    @Test
    fun `fling start velocity - progress per second times 100 times 0_1 times 2, negative, capped at 1000`() {
        flingStartVelocity(progressVelocity = 2f, edgeSwipe = true, gestureProgress = 0.5f) shouldBe
            (-40f plusOrMinus tolerance)
        flingStartVelocity(progressVelocity = 2f, edgeSwipe = false, gestureProgress = 0.5f) shouldBe
            (-20f plusOrMinus tolerance)
        flingStartVelocity(progressVelocity = 500f, edgeSwipe = true, gestureProgress = 0.5f) shouldBe
            (-1000f plusOrMinus tolerance)
        flingStartVelocity(progressVelocity = -3f, edgeSwipe = true, gestureProgress = 0.5f) shouldBe
            (0f plusOrMinus tolerance)
    }

    @Test
    fun `a commit below 0_1 progress flings at no less than 120`() {
        flingStartVelocity(progressVelocity = 0f, edgeSwipe = true, gestureProgress = 0.05f) shouldBe
            (-120f plusOrMinus tolerance)
        flingStartVelocity(progressVelocity = 10f, edgeSwipe = true, gestureProgress = 0.05f) shouldBe
            (-200f plusOrMinus tolerance)
    }

    @Test
    fun `fling bounce shrinks below 100 and never grows past full size`() {
        flingBounceScale(100f) shouldBe (1f plusOrMinus tolerance)
        flingBounceScale(95f) shouldBe (0.95f plusOrMinus tolerance)
        flingBounceScale(104f) shouldBe (1f plusOrMinus tolerance)
    }

    @Test
    fun `the gesture interpolator keeps the ends and leads in the middle`() {
        backGestureInterpolation(0f) shouldBe (0f plusOrMinus tolerance)
        backGestureInterpolation(1f) shouldBe (1f plusOrMinus tolerance)
        backGestureInterpolation(0.5f) shouldBeGreaterThan 0.6f
    }
}
