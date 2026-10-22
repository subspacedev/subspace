// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the byte-unit divisor and the padding
// value below — the divisor is 1024 (binary byte units, named by the constant
// it initializes) and the padding is tokens/spacing.css's --space-* scale.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.getsub.core.model.subscription.Usage
import space.getsub.core.ui.R
import space.getsub.core.ui.theme.tabular
import java.util.Locale
import kotlin.math.pow

private const val BYTES_PER_UNIT = 1024.0
private const val UNLIMITED_BAR_ALPHA = 0.38f

/**
 * A wavy progress bar over a [label] (M8.6 spec §4.1.1). [fraction] is clamped
 * to 0..1, so a plan that has been overrun reads as full rather than overflowing.
 */
@Composable
fun QuotaBar(
    fraction: Float,
    label: String,
    modifier: Modifier = Modifier,
) = QuotaBarContent(fraction, label, barAlpha = 1f, modifier = modifier)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QuotaBarContent(
    fraction: Float,
    label: String,
    barAlpha: Float,
    modifier: Modifier,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Column(modifier = modifier.fillMaxWidth()) {
        LinearWavyProgressIndicator(
            progress = { clamped },
            modifier = Modifier.fillMaxWidth().height(14.dp).alpha(barAlpha),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * [QuotaBar] for a byte count, formatting the label itself. Draws nothing when
 * the provider gave no usable quota: [totalBytes] of 0 is the documented "no
 * cap" convention (ARCHITECTURE.md §A.1), not zero bytes remaining, and a bar
 * reading 100% on an unlimited plan is worse than no bar.
 */
@Composable
fun QuotaBar(
    usedBytes: Long?,
    totalBytes: Long?,
    modifier: Modifier = Modifier,
) {
    if (usedBytes == null || totalBytes == null) return
    if (usedBytes < 0 || totalBytes <= 0) return

    val units = stringArrayResource(R.array.quota_bar_units)
    QuotaBar(
        fraction = usedBytes.toFloat() / totalBytes.toFloat(),
        label =
        stringResource(
            R.string.quota_bar_label,
            usedBytes.toHumanReadableBytes(units),
            totalBytes.toHumanReadableBytes(units),
        ),
        modifier = modifier,
    )
}

/**
 * The bar for a provider's [Usage] (M10-B spec §3.1). Draws nothing for `null`: the provider
 * sent no counters, and the UI must not invent zeros (ARCHITECTURE.md §10.1). A limit draws
 * "38.0 GB / 100.0 GB"; no cap draws a faint full bar, "2.7 TB used · unlimited".
 */
@Composable
fun QuotaBar(
    usage: Usage?,
    modifier: Modifier = Modifier,
) {
    val units = stringArrayResource(R.array.quota_bar_units)
    when (usage) {
        null -> Unit
        is Usage.Limited ->
            QuotaBar(
                fraction = usage.usedBytes.toFloat() / usage.totalBytes.toFloat(),
                label =
                stringResource(
                    R.string.quota_bar_limited_label,
                    usage.usedBytes.toHumanReadableBytes(units),
                    usage.totalBytes.toHumanReadableBytes(units),
                ),
                modifier = modifier,
            )
        is Usage.Unlimited ->
            QuotaBarContent(
                fraction = 1f,
                label = stringResource(R.string.quota_bar_unlimited_label, usage.usedBytes.toHumanReadableBytes(units)),
                // Only the bar is faint: the label must stay readable.
                barAlpha = UNLIMITED_BAR_ALPHA,
                modifier = modifier,
            )
    }
}

private fun Long.toHumanReadableBytes(units: Array<String>): String {
    if (this < BYTES_PER_UNIT) return "$this ${units[0]}"
    val magnitude = (Math.log(toDouble()) / Math.log(BYTES_PER_UNIT)).toInt().coerceAtMost(units.size - 1)
    val scaled = toDouble() / BYTES_PER_UNIT.pow(magnitude)
    return String.format(Locale.US, "%.1f %s", scaled, units[magnitude])
}
