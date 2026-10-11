// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the colour literals of the stand-in schemes below.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.preview

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import space.getsub.core.ui.theme.FriendlyDarkColorScheme
import space.getsub.core.ui.theme.FriendlyLightColorScheme
import space.getsub.core.ui.theme.SubspaceColors
import space.getsub.core.ui.theme.SubspaceThemeFor
import space.getsub.core.ui.theme.friendlyDarkPop
import space.getsub.core.ui.theme.friendlyLightPop

/**
 * Stand-ins for a wallpaper-derived scheme (M8.6 spec §7.1). Paparazzi has no
 * wallpaper, so these fix one: the design's Turn 2a "seed lavender, dark" and
 * "seed terracotta, light" tokens. Deterministic, so goldens are stable.
 */
private val dynamicStandInDark =
    darkColorScheme(
        background = Color(0xFF141218), surface = Color(0xFF141218),
        surfaceContainerLow = Color(0xFF1A181D), surfaceContainer = Color(0xFF1D1B20),
        surfaceContainerHigh = Color(0xFF211F26), surfaceContainerHighest = Color(0xFF2B2930),
        onSurface = Color(0xFFE6E0E9), onSurfaceVariant = Color(0xFFCAC4D0), outlineVariant = Color(0xFF49454F),
        primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72), primaryContainer = Color(0xFF4F378B),
        secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
        secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
        tertiary = Color(0xFFEFB8C8), onTertiary = Color(0xFF492532),
        tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD8E4),
        error = Color(0xFFF2B8B5),
    )

private val dynamicStandInLight =
    lightColorScheme(
        background = Color(0xFFFFF8F6), surface = Color(0xFFFFF8F6),
        surfaceContainerLow = Color(0xFFFEF1EC), surfaceContainer = Color(0xFFFCEAE4),
        surfaceContainerHigh = Color(0xFFF6E5DF), surfaceContainerHighest = Color(0xFFF0DFD9),
        onSurface = Color(0xFF231917), onSurfaceVariant = Color(0xFF53433F), outlineVariant = Color(0xFFD8C2BC),
        primary = Color(0xFF9A4523), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFFFDBCE),
        secondary = Color(0xFF77574C), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDBCE), onSecondaryContainer = Color(0xFF2C160E),
        tertiary = Color(0xFF6A5F2F), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF3E3A7), onTertiaryContainer = Color(0xFF221B00),
        error = Color(0xFFBA1A1A),
    )

private fun ColorScheme.popFromPrimary() = SubspaceColors(primary, onPrimary)

/** The four themes every golden is rendered in (M8.6 spec §7.1). */
enum class ThemeVariant(val fileSuffix: String) {
    FriendlyDark("friendly-dark"),
    FriendlyLight("friendly-light"),
    DynamicDark("dynamic-dark"),
    DynamicLight("dynamic-light"),
}

/** The colour scheme a [ThemeVariant] renders in; unit tests check colour pairs against all four. */
internal fun ThemeVariant.colorScheme(): ColorScheme =
    when (this) {
        ThemeVariant.FriendlyDark -> FriendlyDarkColorScheme
        ThemeVariant.FriendlyLight -> FriendlyLightColorScheme
        ThemeVariant.DynamicDark -> dynamicStandInDark
        ThemeVariant.DynamicLight -> dynamicStandInLight
    }

/**
 * [content] in one [ThemeVariant], on that theme's background. For screenshot
 * tests in any module and for `@Preview`; production code uses `SubspaceTheme`.
 * Motion is reduced by default so animated components render a stable frame.
 */
@Composable
fun SubspacePreviewTheme(
    variant: ThemeVariant,
    reducedMotion: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = variant.colorScheme()
    val colors =
        when (variant) {
            ThemeVariant.FriendlyDark -> friendlyDarkPop
            ThemeVariant.FriendlyLight -> friendlyLightPop
            ThemeVariant.DynamicDark, ThemeVariant.DynamicLight -> scheme.popFromPrimary()
        }
    SubspaceThemeFor(colorScheme = scheme, colors = colors, reducedMotion = reducedMotion) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}
