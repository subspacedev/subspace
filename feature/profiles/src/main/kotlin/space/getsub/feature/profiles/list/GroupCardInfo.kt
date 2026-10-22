// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.getsub.core.model.subscription.Expiry
import space.getsub.core.model.subscription.LinkButton
import space.getsub.core.model.subscription.SubscriptionInfo
import space.getsub.core.model.subscription.isSoon
import space.getsub.core.model.subscription.showInfoBanner
import space.getsub.core.ui.component.QuotaBar
import space.getsub.core.ui.expiryText
import space.getsub.core.ui.openProviderLink
import space.getsub.core.ui.theme.SubspaceShapeTokens
import space.getsub.feature.profiles.R
import space.getsub.core.ui.R as CoreR

/**
 * What the provider says about a subscription, inline on its card (M10-B spec §3.1 items 3 to
 * 7). Links open only on a tap, through [openProviderLink]; every URL here already passed
 * `safeLink`. Nothing in [info] is logged (ARCHITECTURE.md §5.6).
 */
@Composable
internal fun ColumnScope.GroupCardInfo(info: SubscriptionInfo) {
    val context = LocalContext.current
    QuotaBar(usage = info.usage, modifier = Modifier.padding(top = GROUP_HEADER_GAP))
    info.expiry?.let { expiry ->
        ExpiryLine(expiry, renewLink = info.renewLink, onOpen = context::openProviderLink)
    }
    info.announce?.let { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(top = GROUP_HEADER_GAP),
        )
    }
    // The red expiry notice takes the banner's place while it shows.
    if (info.showInfoBanner) {
        InfoBanner(text = info.infoText.orEmpty(), button = info.infoButton, onOpen = context::openProviderLink)
    }
    LinkChips(info, onOpen = context::openProviderLink)
}

@Composable
private fun ExpiryLine(
    expiry: Expiry,
    renewLink: String?,
    onOpen: (String) -> Unit,
) {
    val text = expiryText(expiry)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = GROUP_HEADER_GAP),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (expiry.isSoon) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (expiry.isSoon && renewLink != null) {
            TextButton(onClick = { onOpen(renewLink) }) { Text(stringResource(CoreR.string.subscription_renew)) }
        }
    }
}

/** `sub-info-text` in the app's own colours: the provider's `sub-info-color` is ignored (owner ruling). */
@Composable
private fun InfoBanner(
    text: String,
    button: LinkButton?,
    onOpen: (String) -> Unit,
) {
    Surface(
        shape = SubspaceShapeTokens.bubble,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = GROUP_HEADER_GAP),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (button != null) {
                TextButton(onClick = { onOpen(button.url) }) { Text(button.text) }
            }
        }
    }
}

@Composable
private fun LinkChips(
    info: SubscriptionInfo,
    onOpen: (String) -> Unit,
) {
    val support = info.supportLink
    val account = info.accountLink
    if (support == null && account == null) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = GROUP_HEADER_GAP),
    ) {
        if (support != null) {
            AssistChip(
                onClick = { onOpen(support) },
                label = { Text(stringResource(R.string.subscription_support_link)) },
            )
        }
        if (account != null) {
            AssistChip(
                onClick = { onOpen(account) },
                label = { Text(stringResource(R.string.subscription_account_link)) },
            )
        }
    }
}

/** "N servers", plus " · refreshes every N h" when the provider named an interval (M10-B spec §3.1). */
@Composable
internal fun groupSubtitle(group: ServersGroup): String {
    val count =
        pluralStringResource(CoreR.plurals.group_card_profile_count, group.totalProfileCount, group.totalProfileCount)
    val hours = group.info?.refreshHours?.takeIf { it > 0 } ?: return count
    return stringResource(R.string.servers_group_subtitle_refresh, count, hours)
}
