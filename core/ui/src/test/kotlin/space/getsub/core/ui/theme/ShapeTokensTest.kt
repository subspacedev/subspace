// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import io.kotest.matchers.shouldBe
import org.junit.Test

class ShapeTokensTest {
    private val density = Density(1f)
    private val size = Size(200f, 100f)

    private fun corners(shape: androidx.compose.foundation.shape.CornerBasedShape) =
        listOf(shape.topStart, shape.topEnd, shape.bottomEnd, shape.bottomStart).map { it.toPx(size, density) }

    /** M8.6 spec §2.5: one tight corner, always bottom-start for dialogs and cards. */
    @Test
    fun theTightCornerShapesMatchTheSpec() {
        corners(SubspaceShapeTokens.dialog) shouldBe listOf(32f, 32f, 32f, 12f)
        corners(SubspaceShapeTokens.card) shouldBe listOf(28f, 28f, 28f, 10f)
        corners(SubspaceShapeTokens.cardLead) shouldBe listOf(12f, 34f, 34f, 34f)
        corners(SubspaceShapeTokens.cardTail) shouldBe listOf(34f, 34f, 34f, 12f)
        corners(SubspaceShapeTokens.tile) shouldBe listOf(20f, 8f, 20f, 20f)
        corners(SubspaceShapeTokens.bubble) shouldBe listOf(15f, 6f, 15f, 15f)
    }
}
