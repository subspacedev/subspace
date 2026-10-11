// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

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

/**
 * The system animator scale, kept current while composed. "Remove animations"
 * toggled while the app is open applies at once (M8.6 spec §2.6, device row 9),
 * rather than at the next unrelated recomposition (Part 1 deferred minor).
 */
@Composable
fun rememberAnimatorDurationScale(): Float {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(systemAnimatorDurationScale(context)) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    scale = systemAnimatorDurationScale(context)
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return scale
}
