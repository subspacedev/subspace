// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.getsub.core.model.subscription.isSoon
import space.getsub.core.ui.expiryText
import space.getsub.core.ui.format.formatByteCount
import space.getsub.core.ui.openProviderLink
import space.getsub.core.ui.R as CoreR

/**
 * The active subscription's limit and expiry, as one slim line under the server name (M10-B
 * spec §3.3). Renew opens the link only on a tap, and only for a link `safeLink` already
 * accepted; nothing here is logged (ARCHITECTURE.md §5.6).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubscriptionLine(line: HomeSubscriptionLine) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        line.usage?.let { usage ->
            LinearProgressIndicator(
                progress = { (usage.usedBytes.toFloat() / usage.totalBytes.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
        // Wraps rather than clipping: on a narrow card or a large font the Renew button drops to
        // a second row instead of being pushed off the edge.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            line.usage?.let { usage ->
                Text(
                    text =
                    stringResource(
                        R.string.home_active_server_usage,
                        formatByteCount(usage.usedBytes),
                        formatByteCount(usage.totalBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            line.expiry?.let { expiry ->
                Text(
                    text = expiryText(expiry),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                    if (expiry.isSoon) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            line.renewLink?.let { link ->
                TextButton(onClick = { context.openProviderLink(link) }) {
                    Text(stringResource(CoreR.string.subscription_renew))
                }
            }
        }
    }
}
