// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component.blob

import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.theme.BlobTiming

/** A dim glow's strength: Stalled's tertiaryContainer. */
internal const val GLOW_LEVEL_DIM = 0.35f

/** The off-state tint's strength: a pastel wash, very faint. */
internal const val GLOW_LEVEL_TINT = 0.4f

/** A breathing glow held still under reduced motion: the top of the breath. */
internal const val GLOW_LEVEL_STEADY = 0.5f

/** The breath, from the design snapshot's `breath` keyframes (opacity .08 <-> .5). */
private const val GLOW_LEVEL_LOW = 0.08f
private const val GLOW_LEVEL_HIGH = 0.5f

/**
 * How far any glow is visible beyond the blob on every side: the clearance
 * ConnectBlob's KDoc asks a caller to leave. BlobVisualTest holds every
 * [GlowStyle] to it.
 */
internal val GLOW_CLEARANCE: Dp = 40.dp

/**
 * The glow's canvas reaches this far past the blob, beyond [GLOW_CLEARANCE],
 * so no style's faint tail is cut by the canvas edge.
 */
internal val GLOW_CANVAS_MARGIN: Dp = 64.dp

/** RenderEffect turns a blur radius r into a Gaussian sigma of about 0.58 r (+ 0.5 px). */
private const val RADIUS_TO_SIGMA = 0.57735f

/** Visible: 1.5 sigma past the grown source, about 7 % of the glow's level. */
private const val VISIBLE_SIGMAS = 1.5f

/** Tail: 2.5 sigma, under 1 %. */
private const val TAIL_SIGMAS = 2.5f

/**
 * The geometry of one glow: the blob's path grown by [growth], blurred by
 * [blurRadius]; the layered fallback stacks copies out to [visibleSpread],
 * each [copyAlpha] opaque.
 */
internal data class GlowStyle(
    val growth: Dp,
    val blurRadius: Dp,
    val copyAlpha: Float,
) {
    private val sigma: Dp get() = blurRadius * RADIUS_TO_SIGMA

    fun visibleSpread(): Dp = growth + sigma * VISIBLE_SIGMAS

    fun tail(): Dp = growth + sigma * TAIL_SIGMAS
}

/** Stalled's dim glow: visible to about 27 dp. */
private val StandardGlow = GlowStyle(growth = 10.dp, blurRadius = 20.dp, copyAlpha = 0.09f)

/** Connected's breathing glow: visible to about 40 dp. */
private val WideGlow = GlowStyle(growth = 12.dp, blurRadius = 32.dp, copyAlpha = 0.09f)

/**
 * The off-state tint: barely grown, so there is no rim that reads as an
 * outline, and blurred wide and soft, visible to about 39 dp.
 */
private val SoftGlow = GlowStyle(growth = 4.dp, blurRadius = 40.dp, copyAlpha = 0.06f)

internal fun BlobGlow.style(): GlowStyle =
    when (this) {
        BlobGlow.None, is BlobGlow.DimStill -> StandardGlow
        is BlobGlow.Tint -> SoftGlow
        is BlobGlow.Breathing -> WideGlow
    }

/** The layered fallback: this many grown copies of the path. */
private const val LAYERED_COPIES = 12

/** How the glow is drawn on this device. */
internal enum class GlowTechnique {
    /** The blob's own path, grown and blurred by a RenderEffect (Android 12+). */
    Blur,

    /**
     * Grown copies of the path stacked at low alpha: a stepped falloff that
     * reads close to the blur where RenderEffect does not exist (API 26-30).
     */
    Layered,
}

/** The one place the technique is chosen: RenderEffect exists from Android 12 (API 31). */
internal fun glowTechnique(sdkInt: Int): GlowTechnique =
    if (sdkInt >= Build.VERSION_CODES.S) GlowTechnique.Blur else GlowTechnique.Layered

/**
 * Forces a technique, for snapshot tests only: layoutlib reports a current
 * API level, so without this the API 26-30 fallback would have no golden.
 * Null (the default) chooses by [glowTechnique].
 */
@VisibleForTesting
internal val LocalGlowTechniqueOverride: ProvidableCompositionLocal<GlowTechnique?> =
    staticCompositionLocalOf { null }

/**
 * The glow's level when it does not animate, or null when it breathes. A
 * breathing glow holds at [GLOW_LEVEL_STEADY] under reduced motion: the
 * breath is decorative (M8.6 spec §2.6), the glow itself is not.
 */
internal fun BlobGlow.stillLevel(reduced: Boolean): Float? =
    when (this) {
        BlobGlow.None -> 0f
        is BlobGlow.Tint -> GLOW_LEVEL_TINT
        is BlobGlow.DimStill -> GLOW_LEVEL_DIM
        is BlobGlow.Breathing -> if (reduced) GLOW_LEVEL_STEADY else null
    }

/**
 * The glow's level, read at draw time. The breath's infinite transition is
 * composed only while a breathing glow is shown with motion allowed, so other
 * states pay nothing for it and tests under reduced motion can reach idle.
 */
@Composable
internal fun rememberGlowLevel(
    glow: BlobGlow,
    reduced: Boolean,
): () -> Float {
    val still = glow.stillLevel(reduced)
    if (still != null) return { still }
    val breath =
        rememberInfiniteTransition(label = "glow").animateFloat(
            initialValue = GLOW_LEVEL_LOW,
            targetValue = GLOW_LEVEL_HIGH,
            animationSpec = infiniteRepeatable(tween(BlobTiming.HALO_BREATH_MILLIS), RepeatMode.Reverse),
            label = "breath",
        )
    return breath::value
}

/**
 * The glow's colour, read at draw time. With no glow it heads for the fill
 * while the glow fades out, rather than towards transparent black, which
 * would darken it on the way.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun animatedGlowColor(
    visual: BlobVisual,
    reduced: Boolean,
): () -> Color {
    val scheme = MaterialTheme.colorScheme
    val color by animateColorAsState(
        visual.glow.role?.color(scheme) ?: visual.fill.container(scheme),
        if (reduced) snap() else MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "glow",
    )
    return { color }
}

/** 1 while the state has a glow, 0 when it has none, faded between. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun animatedGlowPresence(
    glow: BlobGlow,
    reduced: Boolean,
): () -> Float {
    val presence by animateFloatAsState(
        if (glow == BlobGlow.None) 0f else 1f,
        if (reduced) snap() else MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "glowPresence",
    )
    return { presence }
}

/**
 * The glow behind a blob of [blobSize]: the blob's own outline from
 * [fillPath], turned by [rotation], in [color], at [alpha] (all read at draw
 * time), shaped by [glow]'s [GlowStyle]. It is laid out larger than the blob and centred on it, so it draws
 * outside its parent's bounds; see ConnectBlob's KDoc for the clearance.
 */
@Suppress("LongParameterList") // the lambdas are read at draw time, so they cannot be folded into one value
@Composable
internal fun BlobGlowLayer(
    blobSize: Dp,
    glow: BlobGlow,
    fillPath: (Path) -> Unit,
    rotation: () -> Float,
    color: () -> Color,
    alpha: () -> Float,
) {
    val technique = LocalGlowTechniqueOverride.current ?: glowTechnique(Build.VERSION.SDK_INT)
    val path = remember { Path() }
    // While a glow fades out to None it keeps the shape it had, rather than
    // jumping to another style mid-fade. A plain holder, not state: it only
    // ever follows the glow argument.
    val lastStyle = remember { arrayOf(glow.style()) }
    if (glow != BlobGlow.None) lastStyle[0] = glow.style()
    val style = lastStyle[0]
    // Built once per density and style, not in the layer block: that block re-runs on
    // every breath frame, because it reads alpha.
    val density = LocalDensity.current
    val blur =
        remember(density, technique, style) {
            if (technique == GlowTechnique.Blur) {
                val r = with(density) { style.blurRadius.toPx() }
                BlurEffect(r, r, TileMode.Decal)
            } else {
                null
            }
        }
    Canvas(
        Modifier
            .requiredSize(blobSize + GLOW_CANVAS_MARGIN * 2)
            .graphicsLayer {
                this.alpha = alpha().coerceIn(0f, 1f)
                renderEffect = blur
            },
    ) {
        // Alpha lives on the layer, but this drawing is still re-recorded
        // every frame while the blob drifts or morphs, since it reads the
        // rotation and the path; a still glow (Stalled) records it once.
        fillPath(path)
        val blob = blobSize.toPx()
        val margin = GLOW_CANVAS_MARGIN.toPx()
        rotate(rotation()) {
            when (technique) {
                GlowTechnique.Blur -> drawGrown(path, color(), blob, margin, style.growth.toPx())
                GlowTechnique.Layered -> {
                    val spread = style.visibleSpread().toPx()
                    val copy = color().copy(alpha = style.copyAlpha)
                    for (i in 1..LAYERED_COPIES) {
                        drawGrown(path, copy, blob, margin, spread * i / LAYERED_COPIES)
                    }
                }
            }
        }
    }
}

/**
 * The unit-square [path] as a blob of [blob] px grown by [growth] px, centred
 * in a canvas [margin] px wider on each side.
 */
private fun DrawScope.drawGrown(
    path: Path,
    color: Color,
    blob: Float,
    margin: Float,
    growth: Float,
) {
    drawUnitPath(path, color, origin = margin - growth, side = blob + 2 * growth)
}
