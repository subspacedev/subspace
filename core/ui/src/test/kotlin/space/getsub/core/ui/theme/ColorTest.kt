// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import kotlin.math.pow

/**
 * WCAG 2 relative-luminance contrast ratio between two opaque sRGB colors.
 * https://www.w3.org/TR/WCAG21/#dfn-relative-luminance
 */
private fun contrastRatio(
    a: Color,
    b: Color,
): Double {
    fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun relativeLuminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}

class ColorTest {
    /**
     * Review Focus 5: every pair a component draws text or an icon with.
     * 4.5:1 is WCAG AA for body text.
     */
    private fun pairs(
        scheme: ColorScheme,
        colors: SubspaceColors,
    ) = listOf(
        "onSurface/background" to (scheme.onSurface to scheme.background),
        "onSurfaceVariant/surfaceContainerHighest" to (scheme.onSurfaceVariant to scheme.surfaceContainerHighest),
        "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
        "onSecondaryContainer/secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
        "onTertiary/tertiary" to (scheme.onTertiary to scheme.tertiary),
        "onTertiaryContainer/tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
        "onError/error" to (scheme.onError to scheme.error),
        "onErrorContainer/errorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
        "onPop/pop" to (colors.onPop to colors.pop),
    )

    @Test
    fun everyTextPairInTheLightFallbackIsReadable() {
        pairs(FriendlyLightColorScheme, friendlyLightPop).forEach { (name, pair) ->
            withClue(name) { contrastRatio(pair.first, pair.second) shouldBeGreaterThan 4.5 }
        }
    }

    @Test
    fun everyTextPairInTheDarkFallbackIsReadable() {
        pairs(FriendlyDarkColorScheme, friendlyDarkPop).forEach { (name, pair) ->
            withClue(name) { contrastRatio(pair.first, pair.second) shouldBeGreaterThan 4.5 }
        }
    }

    /** M8.6 spec §2.2: connected (tertiary) must not look like a button (primary). */
    @Test
    fun connectedIsNotThePrimaryColour() {
        FriendlyLightColorScheme.tertiary shouldNotBe FriendlyLightColorScheme.primary
        FriendlyDarkColorScheme.tertiary shouldNotBe FriendlyDarkColorScheme.primary
    }
}
