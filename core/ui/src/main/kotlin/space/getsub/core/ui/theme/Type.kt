// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:OptIn(ExperimentalTextApi::class)

package space.getsub.core.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import space.getsub.core.ui.R

// The design system's own token file (tokens/fonts.css) flags this as a
// substitution, not a brand decision: "the source repo ships no typeface
// (Android system Roboto only, no font files in the tree) ... Ask the team
// for real brand type if one exists." Roboto Flex / Roboto Mono are the
// nearest Google-Fonts match for M3 Expressive until real brand type shows
// up.
//
// Both files are bundled in res/font rather than loaded through the Google
// Fonts downloadable-font provider: that provider requires Play Services,
// which is proprietary and would foreclose the IzzyOnDroid distribution path
// (ARCHITECTURE.md §14.7) — the same reason §2 specifies ZXing over ML Kit.
// See THIRD_PARTY.md for the license (SIL OFL 1.1).
//
// Both files are variable fonts (single .ttf, `wght` axis). Each Font() below
// pins one weight via FontVariation rather than shipping a separate static
// file per weight, which is the documented way to bundle a variable font in
// Compose.
private fun robotoFlexWeight(weight: FontWeight) =
    Font(
        resId = R.font.roboto_flex,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

private fun robotoMonoWeight(weight: FontWeight) =
    Font(
        resId = R.font.roboto_mono,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

/** `--font-brand` (`tokens/typography.css`): display/headline/title/body/label. */
val RobotoFlexFontFamily =
    FontFamily(
        robotoFlexWeight(FontWeight.Normal),
        robotoFlexWeight(FontWeight.Medium),
        robotoFlexWeight(FontWeight.SemiBold),
        // Only the emphasized label roles (SemiBold + one step) use Bold.
        robotoFlexWeight(FontWeight.Bold),
    )

/**
 * `--font-mono` (`tokens/typography.css`). Not decorative: every
 * machine-generated string in the design — addresses, transports, ports,
 * quota, timestamps — is set in this family, and that's what makes a config
 * value visually separable from prose.
 */
val RobotoMonoFontFamily =
    FontFamily(
        robotoMonoWeight(FontWeight.Normal),
    )

/**
 * The 15 M3 type roles, mapped from the M8.6 spec §2.4.
 */
private val baseline =
    Typography(
        displayLarge = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 57.sp,
            lineHeight = 64.sp,
            letterSpacing = (-0.25).sp,
        ),
        displayMedium = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 45.sp,
            lineHeight = 52.sp,
            letterSpacing = 0.sp,
        ),
        displaySmall = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 36.sp,
            lineHeight = 44.sp,
            letterSpacing = 0.sp,
        ),
        headlineLarge = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = 0.sp,
        ),
        headlineMedium = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 28.sp,
            lineHeight = 36.sp,
            letterSpacing = 0.sp,
        ),
        headlineSmall = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.sp,
        ),
        titleLarge = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.15.sp,
        ),
        titleSmall = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp,
        ),
        bodyLarge = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.5.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.25.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.4.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = RobotoFlexFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp,
        ),
    )

/**
 * The same weight one step (100) heavier, at the same size, line height and
 * tracking: what an `*Emphasized` role means here.
 */
private fun TextStyle.emphasized(): TextStyle =
    copy(fontWeight = FontWeight((fontWeight ?: FontWeight.Normal).weight + EMPHASIS_WEIGHT_STEP))

private const val EMPHASIS_WEIGHT_STEP = 100

/**
 * The 15 baseline roles plus their M3 Expressive `*Emphasized` twins. The
 * 15-argument Typography constructor copies each baseline style into its
 * emphasized slot unchanged (material3 1.5.0-alpha29), which makes
 * emphasized text indistinguishable; this constructor sets them explicitly,
 * each one weight step above its baseline (Normal → Medium → SemiBold → Bold),
 * all weights bundled in [RobotoFlexFontFamily].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val SubspaceTypography =
    Typography(
        displayLarge = baseline.displayLarge,
        displayMedium = baseline.displayMedium,
        displaySmall = baseline.displaySmall,
        headlineLarge = baseline.headlineLarge,
        headlineMedium = baseline.headlineMedium,
        headlineSmall = baseline.headlineSmall,
        titleLarge = baseline.titleLarge,
        titleMedium = baseline.titleMedium,
        titleSmall = baseline.titleSmall,
        bodyLarge = baseline.bodyLarge,
        bodyMedium = baseline.bodyMedium,
        bodySmall = baseline.bodySmall,
        labelLarge = baseline.labelLarge,
        labelMedium = baseline.labelMedium,
        labelSmall = baseline.labelSmall,
        displayLargeEmphasized = baseline.displayLarge.emphasized(),
        displayMediumEmphasized = baseline.displayMedium.emphasized(),
        displaySmallEmphasized = baseline.displaySmall.emphasized(),
        headlineLargeEmphasized = baseline.headlineLarge.emphasized(),
        headlineMediumEmphasized = baseline.headlineMedium.emphasized(),
        headlineSmallEmphasized = baseline.headlineSmall.emphasized(),
        titleLargeEmphasized = baseline.titleLarge.emphasized(),
        titleMediumEmphasized = baseline.titleMedium.emphasized(),
        titleSmallEmphasized = baseline.titleSmall.emphasized(),
        bodyLargeEmphasized = baseline.bodyLarge.emphasized(),
        bodyMediumEmphasized = baseline.bodyMedium.emphasized(),
        bodySmallEmphasized = baseline.bodySmall.emphasized(),
        labelLargeEmphasized = baseline.labelLarge.emphasized(),
        labelMediumEmphasized = baseline.labelMedium.emphasized(),
        labelSmallEmphasized = baseline.labelSmall.emphasized(),
    )

/** OpenType feature for tabular (fixed-width) figures (M8.6 spec §2.4). */
// Suppressed: public interface name TabularFigures (per spec) conflicts with detekt's const-naming rule.
@Suppress("TopLevelPropertyNaming")
const val TabularFigures: String = "tnum"

/** Same style, figures fixed-width, so changing numbers do not jitter. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TabularFigures)

/** Same size and weight in Roboto Mono, for machine strings only (M8.6 spec §2.4). */
fun TextStyle.mono(): TextStyle = copy(fontFamily = RobotoMonoFontFamily)
