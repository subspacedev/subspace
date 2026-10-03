// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.theme

import androidx.compose.ui.text.TextStyle
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test

class TypeTest {
    private val t = SubspaceTypography

    private val pairs: List<Triple<String, TextStyle, TextStyle>> =
        listOf(
            Triple("displayLarge", t.displayLarge, t.displayLargeEmphasized),
            Triple("displayMedium", t.displayMedium, t.displayMediumEmphasized),
            Triple("displaySmall", t.displaySmall, t.displaySmallEmphasized),
            Triple("headlineLarge", t.headlineLarge, t.headlineLargeEmphasized),
            Triple("headlineMedium", t.headlineMedium, t.headlineMediumEmphasized),
            Triple("headlineSmall", t.headlineSmall, t.headlineSmallEmphasized),
            Triple("titleLarge", t.titleLarge, t.titleLargeEmphasized),
            Triple("titleMedium", t.titleMedium, t.titleMediumEmphasized),
            Triple("titleSmall", t.titleSmall, t.titleSmallEmphasized),
            Triple("bodyLarge", t.bodyLarge, t.bodyLargeEmphasized),
            Triple("bodyMedium", t.bodyMedium, t.bodyMediumEmphasized),
            Triple("bodySmall", t.bodySmall, t.bodySmallEmphasized),
            Triple("labelLarge", t.labelLarge, t.labelLargeEmphasized),
            Triple("labelMedium", t.labelMedium, t.labelMediumEmphasized),
            Triple("labelSmall", t.labelSmall, t.labelSmallEmphasized),
        )

    /**
     * The 15-argument Typography constructor copies each baseline style into
     * its emphasized slot unchanged; emphasized must actually be heavier, and
     * otherwise the same role.
     */
    @Test
    fun everyEmphasizedRoleIsOneWeightStepHeavierAndOtherwiseTheSame() {
        pairs.forEach { (name, base, emphasized) ->
            withClue(name) {
                emphasized.fontWeight!!.weight shouldBe base.fontWeight!!.weight + 100
                emphasized.copy(fontWeight = base.fontWeight) shouldBe base
            }
        }
    }
}
