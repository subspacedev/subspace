// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.snapshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
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

private data class Variant(
    val name: String,
    val scheme: ColorScheme,
    val colors: SubspaceColors,
)

private fun ColorScheme.popFromPrimary() = SubspaceColors(primary, onPrimary)

private val variants =
    listOf(
        Variant("friendly-dark", FriendlyDarkColorScheme, friendlyDarkPop),
        Variant("friendly-light", FriendlyLightColorScheme, friendlyLightPop),
        Variant("dynamic-dark", dynamicStandInDark, dynamicStandInDark.popFromPrimary()),
        Variant("dynamic-light", dynamicStandInLight, dynamicStandInLight.popFromPrimary()),
    )

/**
 * Snapshots [content] once per theme variant, on the theme's background,
 * with motion reduced so animated components render a stable frame.
 */
fun Paparazzi.snapshotThemes(
    name: String,
    content: @Composable () -> Unit,
) {
    variants.forEach { v ->
        snapshot(name = "$name-${v.name}") {
            SubspaceThemeFor(colorScheme = v.scheme, colors = v.colors, reducedMotion = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.padding(16.dp)) { content() }
                }
            }
        }
    }
}

/**
 * Snapshots [content] once, in the friendly light theme, with motion left on.
 * Paparazzi captures the first frame, before any animation has advanced, so
 * this shows the shape a component starts from when motion is allowed, which
 * [snapshotThemes] cannot (it reduces motion).
 */
fun Paparazzi.snapshotWithMotion(
    name: String,
    content: @Composable () -> Unit,
) {
    val v = variants.first { it.name == "friendly-light" }
    snapshot(name = "$name-motion-${v.name}") {
        SubspaceThemeFor(colorScheme = v.scheme, colors = v.colors, reducedMotion = false) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.padding(16.dp)) { content() }
            }
        }
    }
}
