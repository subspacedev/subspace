// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A wavy linear progress bar without a label (M8.6 spec §2). [fraction] null is indeterminate, which
 * is the honest rendering when the total is unknown; otherwise it is clamped to 0..1. [QuotaBar]
 * is this plus a caption. Lives here so the expressive opt-in stays in `:core:ui`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WavyProgress(
    fraction: Float?,
    modifier: Modifier = Modifier,
) {
    val bar = modifier.fillMaxWidth().height(14.dp)
    if (fraction == null) {
        LinearWavyProgressIndicator(modifier = bar)
    } else {
        val clamped = fraction.coerceIn(0f, 1f)
        LinearWavyProgressIndicator(progress = { clamped }, modifier = bar)
    }
}
