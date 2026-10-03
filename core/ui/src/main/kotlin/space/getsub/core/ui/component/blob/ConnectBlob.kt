// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the design's literal values below: the
// halo's breath alphas (0.08 <-> 0.5, solid 0.35 under reduced motion), the
// 360-degree turn and its half, and the dp sizes the brief transcribes from
// the design. Each is used once, at the line that explains it.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.component.blob

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import space.getsub.core.model.ConnectionState
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.BlobTiming
import space.getsub.core.ui.theme.LocalReducedMotion
import kotlin.math.floor
import kotlin.math.roundToInt

private const val FULL_TURN = 360f
private const val HALO_ALPHA_LOW = 0.08f
private const val HALO_ALPHA_HIGH = 0.5f
private const val HALO_ALPHA_REDUCED = 0.35f
private val HALO_SPREAD = 16.dp
private val ICON_SIZE = 40.dp
private val BADGE_SIZE = 14.dp
private val BADGE_BACKING = 18.dp

/**
 * Home's main control (M8.6 spec §3.1, §3.3): a MaterialShapes shape whose
 * outline and centre icon differ for every [ConnectionState], so the state is
 * readable without colour (ARCHITECTURE.md §5.5, M8.6 spec §3.2). Everything
 * it draws comes from [blobVisualFor].
 *
 * The click target is the full [size] square. The breathing halo is drawn
 * [HALO_SPREAD] outside that square, so a caller should leave that much room
 * around it. [contentDescription] is the state's label (Part 2), so TalkBack
 * reads the state rather than the shape.
 *
 * Decorative motion (drift, the Connecting shape cycle, the halo's breath)
 * stops under [LocalReducedMotion] (M8.6 spec §2.6); the loader keeps
 * animating, because it is feedback.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectBlob(
    state: ConnectionState,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 208.dp,
) {
    val visual = blobVisualFor(state)
    val reduced = LocalReducedMotion.current
    val scheme = MaterialTheme.colorScheme
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val fill by animateColorAsState(visual.fill.container(scheme), if (reduced) snap() else effects, label = "fill")
    val onFill by animateColorAsState(visual.fill.content(scheme), if (reduced) snap() else effects, label = "onFill")

    val geometry = rememberBlobGeometry(visual.shape, reduced)
    val rotation = rememberBlobRotation(visual.motion, reduced)
    val haloAlpha = haloAlpha(visual.motion, reduced)
    val path = remember { Path() }

    val interactionSource = remember { MutableInteractionSource() }
    val target =
        modifier
            .size(size)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }.clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false, radius = size / 2),
                onClick = onClick,
            )
    Box(
        modifier = target,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            geometry.fillPath(path)
            rotate(rotation.value) {
                val alpha = haloAlpha()
                if (alpha > 0f) {
                    val spread = HALO_SPREAD.toPx()
                    drawUnitPath(path, fill.copy(alpha = alpha), origin = -spread, side = this.size.width + 2 * spread)
                }
                drawUnitPath(path, fill, origin = 0f, side = this.size.width)
            }
        }
        BlobCenterContent(visual.center, fill = fill, onFill = onFill)
    }
}

/** Draws a path in MaterialShapes' unit square into an [side]-px square at [origin]. */
private fun DrawScope.drawUnitPath(
    path: Path,
    color: Color,
    origin: Float,
    side: Float,
) {
    translate(origin, origin) {
        scale(side, side, pivot = Offset.Zero) { drawPath(path, color) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BlobCenterContent(
    center: BlobCenter,
    fill: Color,
    onFill: Color,
) {
    when (center) {
        BlobCenter.Power -> CenterIcon(SubspaceIcons.PowerSettingsNew, onFill)
        BlobCenter.Loader -> LoadingIndicator(color = onFill)
        BlobCenter.Shield -> CenterIcon(SubspaceIcons.ShieldFilled, onFill)
        BlobCenter.ShieldWarning ->
            Box(Modifier.size(ICON_SIZE)) {
                CenterIcon(SubspaceIcons.ShieldFilled, onFill)
                // The badge sits on a disc of the fill colour so it reads as a
                // separate mark rather than merging into the same-tint shield.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(BADGE_BACKING)
                        .background(fill, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(SubspaceIcons.Warning),
                        contentDescription = null,
                        tint = onFill,
                        modifier = Modifier.size(BADGE_SIZE),
                    )
                }
            }
        BlobCenter.VpnLock -> CenterIcon(SubspaceIcons.VpnLock, onFill)
        BlobCenter.Warning -> CenterIcon(SubspaceIcons.Warning, onFill)
    }
}

@Composable
private fun CenterIcon(
    icon: Int,
    tint: Color,
) {
    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(ICON_SIZE))
}

// The Connecting cycle: Circle -> SoftBurst -> Cookie9Sided -> Circle, one
// pass per CONNECTING_DRIFT_MILLIS. MaterialShapes caches its polygons, so
// these are the same instances restingPolygon() returns.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val cycleKeyframes: List<RoundedPolygon> by lazy {
    listOf(MaterialShapes.Circle, MaterialShapes.SoftBurst, MaterialShapes.Cookie9Sided)
}
private val cycleMorphs: List<Morph> by lazy {
    cycleKeyframes.indices.map { i -> Morph(cycleKeyframes[i], cycleKeyframes[(i + 1) % cycleKeyframes.size]) }
}

/**
 * The polygon a state settles on. Morphing settles on Circle (where its cycle
 * starts) when motion is allowed, and holds SoftBurst when it is not, so
 * Connecting still has a shape of its own (M8.6 spec §2.6, §3.2).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun restingPolygon(
    shape: BlobShape,
    reduced: Boolean,
): RoundedPolygon =
    when (shape) {
        BlobShape.Circle -> MaterialShapes.Circle
        BlobShape.Morphing -> if (reduced) MaterialShapes.SoftBurst else MaterialShapes.Circle
        BlobShape.Cookie9 -> MaterialShapes.Cookie9Sided
        BlobShape.Pill -> MaterialShapes.Pill
        BlobShape.Square -> MaterialShapes.Square
        BlobShape.Slanted -> MaterialShapes.Slanted
    }

/**
 * The blob's outline: either a state-change [Morph] at [progress], or, while
 * Connecting with motion allowed, a position in the shape cycle.
 */
private class BlobGeometry(
    initial: RoundedPolygon,
) {
    var target by mutableStateOf(initial)
    var morph by mutableStateOf(Morph(initial, initial))
    var cycling by mutableStateOf(false)
    val progress = Animatable(1f)
    val cyclePhase = Animatable(0f)

    /** The polygon to morph away from when the state changes. */
    fun settled(): RoundedPolygon =
        if (cycling) {
            cycleKeyframes[cyclePhase.value.roundToInt() % cycleKeyframes.size]
        } else {
            target
        }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    fun fillPath(path: Path) {
        path.rewind()
        if (cycling) {
            val phase = cyclePhase.value
            val index = floor(phase).toInt()
            cycleMorphs[index.mod(cycleMorphs.size)].toPath(phase - index, path)
        } else {
            morph.toPath(progress.value, path)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rememberBlobGeometry(
    shape: BlobShape,
    reduced: Boolean,
): BlobGeometry {
    val geometry = remember { BlobGeometry(restingPolygon(shape, reduced)) }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(shape, reduced) {
        val start = geometry.settled()
        geometry.cycling = false
        val end = restingPolygon(shape, reduced)
        if (start !== end || geometry.target !== end) {
            geometry.morph = Morph(start, end)
            geometry.target = end
            if (reduced) {
                geometry.progress.snapTo(1f)
            } else {
                geometry.progress.snapTo(0f)
                geometry.progress.animateTo(1f, spatial)
            }
        }
        if (shape == BlobShape.Morphing && !reduced) {
            geometry.cyclePhase.snapTo(0f)
            geometry.cycling = true
            val stops = cycleMorphs.size.toFloat()
            while (true) {
                geometry.cyclePhase.animateTo(stops, tween(BlobTiming.CONNECTING_DRIFT_MILLIS, easing = LinearEasing))
                geometry.cyclePhase.snapTo(0f)
            }
        }
    }
    return geometry
}

/**
 * Drift: one full turn per [driftMillis]. When the state stops drifting the
 * shape eases back upright, so Square, Pill and Slanted are seen as
 * themselves; under reduced motion it is simply upright.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rememberBlobRotation(
    motion: BlobMotion,
    reduced: Boolean,
): Animatable<Float, *> {
    val rotation = remember { Animatable(0f) }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val period = motion.driftMillis()
    LaunchedEffect(period, reduced) {
        when {
            reduced -> rotation.snapTo(0f)
            period != null ->
                while (true) {
                    val from = rotation.value.mod(FULL_TURN)
                    rotation.snapTo(from)
                    rotation.animateTo(from + FULL_TURN, tween(period, easing = LinearEasing))
                }
            else -> {
                val from = rotation.value.mod(FULL_TURN)
                rotation.snapTo(from)
                rotation.animateTo(if (from > FULL_TURN / 2) FULL_TURN else 0f, spatial)
            }
        }
    }
    return rotation
}

/**
 * The halo's alpha, read at draw time: zero unless [BlobMotion.Breathing];
 * breathing 0.08 <-> 0.5 over HALO_BREATH_MILLIS (the design's `breath`
 * keyframes), or a solid ring when motion is reduced.
 */
@Composable
private fun haloAlpha(
    motion: BlobMotion,
    reduced: Boolean,
): () -> Float =
    when {
        motion != BlobMotion.Breathing -> {
            { 0f }
        }
        reduced -> {
            { HALO_ALPHA_REDUCED }
        }
        else ->
            rememberInfiniteTransition(label = "halo")
                .animateFloat(
                    initialValue = HALO_ALPHA_LOW,
                    targetValue = HALO_ALPHA_HIGH,
                    animationSpec = infiniteRepeatable(tween(BlobTiming.HALO_BREATH_MILLIS), RepeatMode.Reverse),
                    label = "breath",
                )::value
    }
