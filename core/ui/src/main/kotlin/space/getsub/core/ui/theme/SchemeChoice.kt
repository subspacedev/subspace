// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.theme

import android.content.Context
import android.provider.Settings

/** Which colour scheme [SubspaceTheme] draws with (M8.6 spec §2.1). */
enum class SchemeChoice { DynamicLight, DynamicDark, FallbackLight, FallbackDark }

/** Material You exists from Android 12 (API 31). */
private const val DYNAMIC_COLOR_MIN_API = 31

/**
 * Wallpaper colours when the user wants them and the platform has them;
 * otherwise the friendly fallback (M8.6 spec §2.1).
 */
fun chooseScheme(
    apiLevel: Int,
    dynamicColor: Boolean,
    dark: Boolean,
): SchemeChoice {
    val dynamic = dynamicColor && apiLevel >= DYNAMIC_COLOR_MIN_API
    return when {
        dynamic && dark -> SchemeChoice.DynamicDark
        dynamic -> SchemeChoice.DynamicLight
        dark -> SchemeChoice.FallbackDark
        else -> SchemeChoice.FallbackLight
    }
}

/**
 * Decorative motion (the blob's drift and the breathing halo) is off in
 * lightweight mode or when the system's "Remove animations" is on
 * (M8.6 spec §2.6). Loading indicators are feedback and keep animating.
 */
fun reducedMotion(
    lightweight: Boolean,
    animatorDurationScale: Float,
): Boolean = lightweight || animatorDurationScale == 0f

/** The system animator scale; 0 means "Remove animations" is on. */
fun systemAnimatorDurationScale(context: Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
