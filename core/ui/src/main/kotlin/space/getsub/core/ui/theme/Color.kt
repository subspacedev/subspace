// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule would otherwise flag every ARGB literal below.
// These are the ported design tokens themselves — the ARGB hex *is* the
// value, already named by the val it's assigned to; extracting each into a
// second named constant would just rename the same magic number twice.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The friendly fallback (M8.6 spec §2.2, the design's Turn 2b): periwinkle,
 * mint and coral on warm neutrals. Used below Android 12, and whenever the
 * user turns dynamic colour off. Values are the design snapshot's tokens,
 * copied verbatim; `docs/agent/research/2026-10-03-claude-design-snapshot/`.
 */
val FriendlyDarkColorScheme: ColorScheme =
    darkColorScheme(
        background = Color(0xFF16131C),
        surface = Color(0xFF16131C),
        surfaceDim = Color(0xFF16131C),
        surfaceBright = Color(0xFF3A3345),
        surfaceContainerLowest = Color(0xFF110E16),
        surfaceContainerLow = Color(0xFF1A1621),
        surfaceContainer = Color(0xFF1E1A26),
        surfaceContainerHigh = Color(0xFF251F2F),
        surfaceContainerHighest = Color(0xFF2F2839),
        onBackground = Color(0xFFF1EAF6),
        onSurface = Color(0xFFF1EAF6),
        surfaceVariant = Color(0xFF2F2839),
        onSurfaceVariant = Color(0xFFCFC4DA),
        outline = Color(0xFF968CA2),
        outlineVariant = Color(0xFF4A4056),
        primary = Color(0xFFB7A8FF),
        onPrimary = Color(0xFF26135E),
        primaryContainer = Color(0xFF3E2E86),
        onPrimaryContainer = Color(0xFFE4DFFF),
        inversePrimary = Color(0xFF5A48C8),
        secondary = Color(0xFFFFB59A),
        onSecondary = Color(0xFF4A1800),
        secondaryContainer = Color(0xFF3A2F52),
        onSecondaryContainer = Color(0xFFEADDFF),
        tertiary = Color(0xFF7DE0B5),
        onTertiary = Color(0xFF003824),
        tertiaryContainer = Color(0xFF0F4A36),
        onTertiaryContainer = Color(0xFFA4F5D0),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        inverseSurface = Color(0xFFF1EAF6),
        inverseOnSurface = Color(0xFF221A26),
        scrim = Color(0xFF000000),
        surfaceTint = Color(0xFFB7A8FF),
    )

val FriendlyLightColorScheme: ColorScheme =
    lightColorScheme(
        background = Color(0xFFFFF9F3),
        surface = Color(0xFFFFF9F3),
        surfaceDim = Color(0xFFE2D8CF),
        surfaceBright = Color(0xFFFFF9F3),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFBF2EA),
        surfaceContainer = Color(0xFFF7EDE4),
        surfaceContainerHigh = Color(0xFFF1E6DC),
        surfaceContainerHighest = Color(0xFFEADFD4),
        onBackground = Color(0xFF221A26),
        onSurface = Color(0xFF221A26),
        surfaceVariant = Color(0xFFEADFD4),
        onSurfaceVariant = Color(0xFF4D4356),
        outline = Color(0xFF7E7387),
        outlineVariant = Color(0xFFD6C9DC),
        primary = Color(0xFF5A48C8),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE4DFFF),
        onPrimaryContainer = Color(0xFF1B0F4A),
        inversePrimary = Color(0xFFB7A8FF),
        // #C4572E gave white onSecondary only 4.42:1. #C1562E is the nearest darker
        // step that keeps the hue (16.3° vs 16.4° HSL) and clears 4.5:1 (4.53:1).
        secondary = Color(0xFFC1562E),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEAE2FF),
        onSecondaryContainer = Color(0xFF1B0F4A),
        tertiary = Color(0xFF0E7A55),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFC4F2DC),
        onTertiaryContainer = Color(0xFF00261A),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        inverseSurface = Color(0xFF382F3C),
        inverseOnSurface = Color(0xFFFBF2EA),
        scrim = Color(0xFF000000),
        surfaceTint = Color(0xFF5A48C8),
    )

/** The coral pop, used once per screen (M8.6 spec §2.3). */
val friendlyDarkPop = SubspaceColors(pop = Color(0xFFFF9E80), onPop = Color(0xFF3D0E00))
val friendlyLightPop = SubspaceColors(pop = Color(0xFFE8603C), onPop = Color(0xFF3D0E00))
