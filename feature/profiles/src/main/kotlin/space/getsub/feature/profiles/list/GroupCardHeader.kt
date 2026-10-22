// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.component.GroupCardUpdateRow
import space.getsub.core.ui.component.QuotaBar
import space.getsub.feature.profiles.R
import space.getsub.feature.profiles.RouteExclusionRequestContent

internal val GROUP_HEADER_GAP = 8.dp

/**
 * What a group's card shows above its rows: quota, last update, and the provider's pending
 * redirect, resolver and excluded-routes requests.
 */
@Composable
internal fun ColumnScope.GroupCardHeader(
    group: ServersGroup,
    actions: ServersGroupListActions,
) {
    val info = group.info
    if (info != null) {
        // M10-B spec §3.1, in order: usage, expiry, announcement, info banner, links. The
        // title and subline are the card's own title row.
        GroupCardInfo(info)
    } else {
        QuotaBar(
            usedBytes = group.quotaUsedBytes,
            totalBytes = group.quotaTotalBytes,
            modifier = Modifier.padding(top = GROUP_HEADER_GAP),
        )
    }
    // onUpdate is null for a MANUAL group (subscriptionId is null there) —
    // no Update affordance without an id to sync.
    GroupCardUpdateRow(
        groupName = group.name,
        lastFetchedAtEpochMillis = group.lastFetchedAtEpochMillis,
        onUpdate = group.subscriptionId?.let { id -> { actions.onUpdateSubscription(id) } },
    )
    if (group.redirectRequestHosts.isNotEmpty() && group.subscriptionId != null) {
        GroupTrustRequest(
            text =
            stringResource(R.string.subscription_redirect_request, group.redirectRequestHosts.joinToString(", ")),
            onAllow = { actions.onAllowRedirects(group.subscriptionId) },
            onDeny = { actions.onDenyRedirects(group.subscriptionId) },
        )
    }
    val resolverHost = group.resolverRequestHost
    if (resolverHost != null && group.subscriptionId != null) {
        GroupTrustRequest(
            text = stringResource(R.string.subscription_resolver_request, resolverHost),
            onAllow = { actions.onAllowResolver(group.subscriptionId, resolverHost) },
            onDeny = { actions.onDenyResolver(group.subscriptionId, resolverHost) },
        )
    }
    val routes = group.routeExclusionRequest
    if (routes != null && group.subscriptionId != null) {
        RouteExclusionRequestContent(
            request = routes,
            onAllow = { actions.onRouteExclusionAnswered(group.subscriptionId, routes.fingerprint, true) },
            onDeny = { actions.onRouteExclusionAnswered(group.subscriptionId, routes.fingerprint, false) },
            modifier = Modifier.padding(top = GROUP_HEADER_GAP),
        )
    }
}

/** A provider's request for trust (redirects, M9-B; its DoH resolver, M9-C), compact: text, Allow, Don't allow. */
@Composable
private fun GroupTrustRequest(
    text: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = GROUP_HEADER_GAP)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDeny) { Text(stringResource(R.string.subscription_redirect_deny)) }
            TextButton(onClick = onAllow) { Text(stringResource(R.string.subscription_redirect_allow)) }
        }
    }
}
