// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import space.getsub.core.ui.component.BannerTone
import space.getsub.core.ui.component.StatusChip
import space.getsub.core.ui.component.TonalBanner
import space.getsub.core.ui.component.blob.ConnectBlob
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.LocalSubspaceColors
import space.getsub.core.ui.theme.tabular

private const val UPTIME_TICK_MILLIS = 1_000L
private const val MILLIS_PER_SECOND = 1_000L
private val BLOB_SIZE = 190.dp

/** Title row: "Subspace" and the history icon (M8.6 spec fix S3). */
@Composable
internal fun HomeTopBar(
    onOpenSessionLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        IconButton(onClick = onOpenSessionLog) {
            Icon(
                painter = painterResource(SubspaceIcons.History),
                contentDescription = stringResource(R.string.home_open_session_log),
            )
        }
    }
}

/** The blob, the action button, label, detail and the unsupported note. */
@Composable
internal fun HomeHero(
    state: HomeState,
    actions: HomeActions,
    nowMillis: () -> Long,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(state.connection.labelRes())
    val action = state.primaryAction()
    // canConnect gates the tap rather than the blob (which has no notion of
    // "nothing to connect to"); see HomeState.canConnect's KDoc. Reconnecting's
    // Disconnect is that state's only in-app exit (M8 spec §7.3).
    val onTap: () -> Unit =
        when (action) {
            HomePrimaryAction.Connect -> { { if (state.canConnect) actions.onConnect() } }
            HomePrimaryAction.Disconnect -> { { if (state.canDisconnect) actions.onDisconnect() } }
            HomePrimaryAction.None -> { {} }
        }
    val actionText =
        when (action) {
            HomePrimaryAction.Connect -> stringResource(R.string.home_action_connect)
            HomePrimaryAction.Disconnect -> stringResource(R.string.home_action_disconnect)
            HomePrimaryAction.None -> null
        }
    val tappable =
        action == HomePrimaryAction.Disconnect || (action == HomePrimaryAction.Connect && state.canConnect)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectBlob(
            state = state.connection,
            onClick = onTap,
            contentDescription = label,
            onClickLabel = actionText,
            enabled = tappable,
            size = BLOB_SIZE,
            // The halo's clearance (ConnectBlob's KDoc).
            modifier = Modifier.padding(16.dp),
        )

        if (actionText != null) HomeActionButton(action, actionText, tappable, onTap)

        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )

        HomeDetailLine(state.connection.detail(), nowMillis)

        if (state.activeProfileUnsupported) {
            Text(
                text = stringResource(R.string.home_active_server_unsupported),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.connection.isBlocked()) {
            TonalBanner(
                icon = SubspaceIcons.VpnLock,
                text = stringResource(R.string.home_blocked_banner),
                tone = BannerTone.Blocked,
            )
        }
    }
}

/** The one button under the blob: the screen's single `pop` for Connect, tonal for Disconnect. */
@Composable
private fun HomeActionButton(
    action: HomePrimaryAction,
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (action == HomePrimaryAction.Connect) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors =
            ButtonDefaults.buttonColors(
                containerColor = LocalSubspaceColors.current.pop,
                contentColor = LocalSubspaceColors.current.onPop,
            ),
        ) { Text(text) }
    } else {
        FilledTonalButton(onClick = onClick) { Text(text) }
    }
}

@Composable
private fun HomeDetailLine(
    detail: HomeDetail,
    nowMillis: () -> Long,
) {
    val failed = detail is HomeDetail.FailureText
    Text(
        text = detailText(detail, nowMillis),
        style =
        if (failed) {
            MaterialTheme.typography.bodySmall
        } else {
            MaterialTheme.typography.bodyMedium.tabular()
        },
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** Routing and Apps chips (M8.6 spec fix A9); one line each, ellipsized. */
@Composable
internal fun HomeChips(
    state: HomeState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        StatusChip(
            icon = SubspaceIcons.Route,
            label = state.activeRuleSetName ?: stringResource(R.string.home_chip_routing_off),
            onClick = actions.onOpenRouting,
            modifier = Modifier.weight(1f, fill = false),
        )
        StatusChip(
            icon = SubspaceIcons.Apps,
            label = appsChipText(state.perApp.toAppsChip()),
            onClick = actions.onOpenPerApp,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun appsChipText(chip: AppsChip): String =
    when (chip) {
        AppsChip.AllApps -> stringResource(R.string.home_chip_apps_all)
        is AppsChip.AllowList -> pluralStringResource(R.plurals.home_chip_apps_allow, chip.count, chip.count)
        is AppsChip.DenyList -> pluralStringResource(R.plurals.home_chip_apps_deny, chip.count, chip.count)
    }

@Composable
private fun detailText(
    detail: HomeDetail,
    nowMillis: () -> Long,
): String =
    when (detail) {
        HomeDetail.TapToConnect -> stringResource(R.string.home_detail_disconnected)
        is HomeDetail.Step -> stringResource(R.string.home_detail_step, detail.number, detail.of)
        is HomeDetail.Uptime ->
            stringResource(
                if (detail.idle) R.string.home_detail_uptime_idle else R.string.home_detail_uptime,
                DateUtils.formatElapsedTime(elapsedSecondsSince(detail.sinceEpochMillis, nowMillis)),
            )
        HomeDetail.NothingComingBack -> stringResource(R.string.home_detail_stalled)
        HomeDetail.NotProtectedTapToStop -> stringResource(R.string.home_detail_reconnecting_open)
        HomeDetail.KillSwitchHolding -> stringResource(R.string.home_detail_blocked)
        HomeDetail.TearingDown -> stringResource(R.string.home_detail_disconnecting)
        // Redacted at construction (ARCHITECTURE.md §5.6), so safe to show; the only
        // diagnostic a user can hand back, since the config itself is never logged.
        is HomeDetail.FailureText -> detail.text
    }

/**
 * Seconds since [sinceEpochMillis], ticking once a second while composed.
 *
 * A real, device-clock-derived measurement, not a placeholder. [nowMillis] is a
 * parameter so a test can pin the clock.
 */
@Composable
private fun elapsedSecondsSince(
    sinceEpochMillis: Long,
    nowMillis: () -> Long,
): Long {
    var elapsed by remember(sinceEpochMillis) {
        mutableLongStateOf((nowMillis() - sinceEpochMillis) / MILLIS_PER_SECOND)
    }
    LaunchedEffect(sinceEpochMillis) {
        while (true) {
            delay(UPTIME_TICK_MILLIS)
            elapsed = (nowMillis() - sinceEpochMillis) / MILLIS_PER_SECOND
        }
    }
    return elapsed
}
