// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

private val LINE_GAP = 4.dp

/**
 * The provider's excluded-routes request (M9-E spec §4), shared by the Servers group card and
 * subscription details: what would bypass the VPN, every range by name, what is refused anyway,
 * then Don't allow and Allow.
 *
 * ARCHITECTURE.md §A.1: this is a Dangerous directive and the confirm action is not the prominent
 * or default-focused one — Don't allow comes first and is the outlined button, Allow is a text
 * button, and nothing requests focus.
 */
@Composable
internal fun RouteExclusionRequestContent(
    request: RouteExclusionRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
        Text(
            text = stringResource(R.string.subscription_route_exclusion_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.subscription_route_exclusion_body, request.applied.joinToString(", ")),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (request.refused.isNotEmpty()) {
            val refused = request.refused.joinToString(", ")
            Text(
                text = stringResource(R.string.subscription_route_exclusion_refused, refused),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(LINE_GAP, Alignment.End),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(onClick = onDeny) { Text(stringResource(R.string.subscription_redirect_deny)) }
            TextButton(onClick = onAllow) { Text(stringResource(R.string.subscription_redirect_allow)) }
        }
    }
}
