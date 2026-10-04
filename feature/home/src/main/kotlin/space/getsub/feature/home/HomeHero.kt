// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import space.getsub.core.ui.component.blob.ConnectBlobDefaults
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.tabular

private const val UPTIME_TICK_MILLIS = 1_000L
private const val MILLIS_PER_SECOND = 1_000L
private val BLOB_SIZE = 190.dp

/**
 * Layout space above and below the blob, deliberately less than the glow's
 * reach (ConnectBlobDefaults.GlowClearance, 56 dp): the faint outer glow may
 * pass under the title row and the state label, which draw over it. Nothing
 * here clips it, and Home's column clips only at the scroll viewport.
 */
private val BLOB_VERTICAL_SPACE = 28.dp

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

/**
 * The blob (with the action word inside it), label, detail and the
 * unsupported note. There is no separate Connect / Disconnect button: the
 * blob is the control (M8.6 spec §4.1, amended 2026-10-04 at the owner's
 * request).
 */
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
    val tappable =
        action == HomePrimaryAction.Disconnect || (action == HomePrimaryAction.Connect && state.canConnect)
    // The word is offered only when a tap will do it: with nothing chosen,
    // "Choose a server first" (the detail line) explains instead, and
    // Connecting / Disconnecting show only the loader.
    val actionText =
        if (!tappable) {
            null
        } else {
            when (action) {
                HomePrimaryAction.Connect -> stringResource(R.string.home_action_connect)
                HomePrimaryAction.Disconnect -> stringResource(R.string.home_action_disconnect)
                HomePrimaryAction.None -> null
            }
        }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectBlob(
            state = state.connection,
            onClick = onTap,
            contentDescription = label,
            actionLabel = actionText,
            enabled = tappable,
            size = BLOB_SIZE,
            // The glow's full reach sideways; less above and below (BLOB_VERTICAL_SPACE).
            modifier =
            Modifier.padding(horizontal = ConnectBlobDefaults.GlowClearance, vertical = BLOB_VERTICAL_SPACE),
        )

        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )

        HomeDetailLine(state.detail(), nowMillis)

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

@Composable
private fun HomeDetailLine(
    detail: HomeDetail?,
    nowMillis: () -> Long,
) {
    if (detail == null) return
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
        HomeDetail.ChooseServer -> stringResource(R.string.home_detail_choose_server)
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
