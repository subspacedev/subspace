// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.R
import space.getsub.core.ui.theme.LocalReducedMotion
import space.getsub.core.ui.theme.SubspaceShapeTokens
import space.getsub.core.ui.theme.tabular
import kotlin.math.roundToInt

private const val BAR_MAX_HEIGHT_DP = 56
private const val BAR_MIN_HEIGHT_DP = 3
private const val ACCENT_BAR_COUNT = 4
private const val MILLIS_PER_SECOND = 1_000L
private val BAR_GAP = 3.dp
private val BAR_SHAPE = RoundedCornerShape(3.dp)

/**
 * Bar heights in dp, scaled to the largest bucket, never below [minHeightDp]
 * so an idle minute still draws a stub (the design's 3 px floor).
 */
fun throughputBars(
    buckets: List<Long>,
    maxHeightDp: Int = BAR_MAX_HEIGHT_DP,
    minHeightDp: Int = BAR_MIN_HEIGHT_DP,
): List<Int> {
    val max = buckets.maxOrNull() ?: 0L
    if (max <= 0L) return buckets.map { minHeightDp }
    return buckets.map { b -> maxOf(minHeightDp, ((b.toDouble() / max) * maxHeightDp).roundToInt()) }
}

/** The busiest bucket as bytes per second (M8.6 spec §4.1.1). */
fun peakBytesPerSecond(
    buckets: List<Long>,
    bucketMillis: Long,
): Long {
    val max = buckets.maxOrNull() ?: return 0L
    return max * MILLIS_PER_SECOND / bucketMillis
}

/**
 * The last hour of traffic as a row of bars, newest on the right (M8.6 spec
 * §4.1.1). Renders from [buckets] only; the Home screen feeds it real data in
 * Part 2. Connected, the newest four bars are `tertiary` and the rest
 * `primary`; disconnected, every bar is `outlineVariant`.
 */
@Suppress("LongParameterList")
@Composable
fun ThroughputCard(
    buckets: List<Long>,
    bucketMillis: Long,
    connected: Boolean,
    peakText: (bytesPerSecond: Long) -> String,
    title: String,
    windowLabel: String,
    modifier: Modifier = Modifier,
) {
    val heights = throughputBars(buckets)
    val peak = peakText(peakBytesPerSecond(buckets, bucketMillis))
    Surface(
        shape = SubspaceShapeTokens.cardTail,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    windowLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth().height(BAR_MAX_HEIGHT_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
                verticalAlignment = Alignment.Bottom,
            ) {
                val newestFrom = heights.size - ACCENT_BAR_COUNT
                heights.forEachIndexed { i, h ->
                    val color =
                        when {
                            !connected -> MaterialTheme.colorScheme.outlineVariant
                            i >= newestFrom -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    Bar(h.dp, color, Modifier.weight(1f))
                }
            }
            Text(
                stringResource(R.string.throughput_axis, peak),
                style = MaterialTheme.typography.labelMedium.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Bar(
    height: Dp,
    color: Color,
    modifier: Modifier,
) {
    val animated by animateDpAsState(
        targetValue = height,
        animationSpec = if (LocalReducedMotion.current) snap() else MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "throughput-bar",
    )
    Box(modifier.height(animated).clip(BAR_SHAPE).background(color))
}
