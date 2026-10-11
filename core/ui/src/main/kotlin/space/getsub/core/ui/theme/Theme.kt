// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package space.getsub.core.ui.theme

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * True when decorative motion is off (M8.6 spec §2.6). Components read this,
 * never the settings directly.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Subspace's theme: Material 3 Expressive, with Material You colour when the
 * user wants it and the platform has it, and the friendly fallback otherwise
 * (M8.6 spec §2.1). Dynamic colour is allowed to recolour the connected state;
 * that is why every connection state also differs in label, icon and shape
 * (ARCHITECTURE.md §5.5, M8.6 spec §3.2).
 */
// chooseScheme returns a Dynamic* choice only on API 31+, so the dynamic*ColorScheme
// calls are unreachable below it; lint cannot see that through the `when`.
@SuppressLint("NewApi")
@Composable
fun SubspaceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val (scheme, colors) =
        when (chooseScheme(Build.VERSION.SDK_INT, dynamicColor, darkTheme)) {
            SchemeChoice.DynamicDark -> dynamicDarkColorScheme(context).withPop()
            SchemeChoice.DynamicLight -> dynamicLightColorScheme(context).withPop()
            SchemeChoice.FallbackDark -> FriendlyDarkColorScheme to friendlyDarkPop
            SchemeChoice.FallbackLight -> FriendlyLightColorScheme to friendlyLightPop
        }
    SubspaceThemeFor(colorScheme = scheme, colors = colors, reducedMotion = reducedMotion, content = content)
}

/** With dynamic colour the pop is the scheme's own primary (M8.6 spec §2.3). */
private fun ColorScheme.withPop(): Pair<ColorScheme, SubspaceColors> = this to SubspaceColors(primary, onPrimary)

/** The theme with an explicit scheme. For screenshot tests and previews. */
@Composable
internal fun SubspaceThemeFor(
    colorScheme: ColorScheme,
    colors: SubspaceColors,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSubspaceColors provides colors,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = SubspaceShapes,
            typography = SubspaceTypography,
            content = content,
        )
    }
}
