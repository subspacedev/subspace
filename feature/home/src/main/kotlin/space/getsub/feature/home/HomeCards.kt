// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.getsub.core.data.StoredProfile
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.ThroughputWindow
import space.getsub.core.model.TrafficSample
import space.getsub.core.ui.component.Outcome
import space.getsub.core.ui.component.StatTile
import space.getsub.core.ui.component.ThroughputCard
import space.getsub.core.ui.format.flagIn
import space.getsub.core.ui.format.formatByteCount
import space.getsub.core.ui.format.nameWithoutFlag
import space.getsub.core.ui.format.protocolDisplayName
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.SubspaceShapeTokens
import space.getsub.core.ui.theme.mono

/** Lightweight mode ([HomeState.trafficHidden]) hides every traffic surface, in every state. */
internal fun HomeState.showsTraffic(): Boolean = !trafficHidden

/**
 * The sample to show, or null. [HomeState.traffic] is a cache that can outlive an
 * in-session disconnect (see its KDoc), so it counts only while
 * [ConnectionState.Connected].
 */
internal fun HomeState.liveSample(): TrafficSample? = traffic.takeIf { connection is ConnectionState.Connected }

/**
 * The throughput history to draw. Anything but exactly
 * [ThroughputWindow.BUCKET_COUNT] buckets is malformed and drawn flat, never
 * truncated or padded: a guessed history would be a fabricated one.
 */
internal fun HomeState.throughputBuckets(): List<Long> =
    liveSample()?.throughput?.takeIf { it.size == ThroughputWindow.BUCKET_COUNT }
        ?: List(ThroughputWindow.BUCKET_COUNT) { 0L }

/**
 * The outcome tag for a By server row. Only a generated config has the
 * `proxy`/`direct`/`block` outbounds this app names; on a hand-written config the
 * same words mean whatever its author meant, so no tag.
 */
internal fun outcomeForTag(
    tag: String,
    generatedConfig: Boolean,
): Outcome? =
    if (!generatedConfig) {
        null
    } else {
        when (tag) {
            "proxy" -> Outcome.Proxy
            "direct" -> Outcome.Direct
            "block" -> Outcome.Block
            else -> null
        }
    }

/** What the latency tile says; [StatTilesRow] maps each to a string. Mirrors the server list's pill. */
internal sealed interface LatencyLabel {
    data object Testing : LatencyLabel

    data object None : LatencyLabel

    data class Ms(val ms: Int) : LatencyLabel

    data object Unsupported : LatencyLabel

    data object ForeignVpn : LatencyLabel

    /** M9-D1 spec §2: the certificate probe named the failure; the fix is the server's pin. */
    data object CertUntrusted : LatencyLabel

    data object Failed : LatencyLabel
}

/** `delayMillis` is read on `OK` alone (M8.5 spec §10.1). */
internal fun HomeState.latencyLabel(): LatencyLabel {
    val result = latency
    return when {
        isMeasuringLatency -> LatencyLabel.Testing
        result == null -> LatencyLabel.None
        result.outcome == LatencyOutcome.OK -> LatencyLabel.Ms(result.delayMillis)
        result.outcome == LatencyOutcome.UNSUPPORTED -> LatencyLabel.Unsupported
        result.outcome == LatencyOutcome.FOREIGN_VPN -> LatencyLabel.ForeignVpn
        result.outcome == LatencyOutcome.CERT_UNTRUSTED -> LatencyLabel.CertUntrusted
        else -> LatencyLabel.Failed
    }
}

/**
 * DOWN, UP and LATENCY, in that order, at equal height.
 *
 * LATENCY: every non-`OK` outcome renders text rather than a number.
 * `delayMillis` is read on the `OK` branch alone: libXray reports a failed ping
 * as a `10000`/`11000` sentinel, and `StatTile` formats nothing itself, so this
 * branch is where M8.5 spec §10.1 is honoured or violated. Tapping measures, and
 * so does ping-on-launch once per session. Nothing else: no measurement on
 * connect, and no timer.
 *
 * DOWN/UP render unless lightweight mode is on ([showsTraffic]). Outside a live
 * session they show an em dash rather than `0 B`: there is no session to have
 * measured. Not tappable: there is no on-demand action for traffic.
 */
@Composable
internal fun StatTilesRow(
    state: HomeState,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latencyValue =
        when (val label = state.latencyLabel()) {
            LatencyLabel.Testing -> stringResource(R.string.home_latency_testing)
            LatencyLabel.None -> stringResource(R.string.home_latency_none)
            is LatencyLabel.Ms -> stringResource(R.string.home_latency_ms, label.ms)
            LatencyLabel.Unsupported -> stringResource(R.string.home_latency_unsupported)
            LatencyLabel.ForeignVpn -> stringResource(R.string.home_latency_foreign_vpn)
            LatencyLabel.CertUntrusted -> stringResource(R.string.home_latency_cert_untrusted)
            LatencyLabel.Failed -> stringResource(R.string.home_latency_failed)
        }
    val latencyHint = stringResource(state.latencyHintRes)
    val none = stringResource(R.string.home_latency_none)
    val live = state.liveSample()
    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tile = Modifier.weight(1f).fillMaxHeight()
        if (state.showsTraffic()) {
            StatTile(
                icon = SubspaceIcons.SouthEast,
                value = live?.let { formatByteCount(it.downlinkBytes) } ?: none,
                label = stringResource(R.string.home_traffic_down),
                modifier = tile,
            )
            StatTile(
                icon = SubspaceIcons.NorthEast,
                value = live?.let { formatByteCount(it.uplinkBytes) } ?: none,
                label = stringResource(R.string.home_traffic_up),
                modifier = tile,
            )
        }
        StatTile(
            icon = SubspaceIcons.NetworkPing,
            value = latencyValue,
            label = stringResource(R.string.home_latency_label),
            modifier = tile,
            // Disabled, not action-less, while a test runs or no server is
            // chosen: TalkBack then says the test exists but cannot run now.
            onClick = onTest,
            enabled = state.activeProfile != null && !state.isMeasuringLatency,
            actionDescription = latencyHint,
        )
    }
}

/** The 60-minute throughput history; absent in lightweight mode. */
@Composable
internal fun HomeThroughputCard(
    state: HomeState,
    modifier: Modifier = Modifier,
) {
    if (!state.showsTraffic()) return
    // Resolved here: the peakText lambda below is not @Composable.
    val noPeak = stringResource(R.string.home_throughput_peak_none)
    val peakFormat = stringResource(R.string.home_throughput_peak)
    val live = state.liveSample() != null
    ThroughputCard(
        buckets = state.throughputBuckets(),
        bucketMillis = ThroughputWindow.BUCKET_MILLIS,
        connected = live,
        peakText = { bps -> if (live) peakFormat.format(formatByteCount(bps)) else noPeak },
        title = stringResource(R.string.home_throughput_title),
        windowLabel = stringResource(R.string.home_throughput_window),
        modifier = modifier,
    )
}

/**
 * The active server, and where tapping it goes: the server list when at least one
 * profile exists (that is where a server is chosen), import only when the store is
 * genuinely empty. Protocol and transport are shown; the address never is
 * (ARCHITECTURE.md §5.6).
 */
@Composable
internal fun ActiveServerCard(
    state: HomeState,
    onNavigateToServers: () -> Unit,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeProfile = state.activeProfile
    val headline: String
    val actionDescription: String
    val onClick: () -> Unit
    when {
        activeProfile != null -> {
            headline = nameWithoutFlag(activeProfile.name)
            actionDescription = stringResource(R.string.home_active_server_change_description)
            onClick = onNavigateToServers
        }

        state.hasAnyProfile -> {
            headline = stringResource(R.string.home_choose_a_server)
            actionDescription = headline
            onClick = onNavigateToServers
        }

        else -> {
            headline = stringResource(R.string.home_no_servers_yet)
            actionDescription = stringResource(R.string.home_add_first_server)
            onClick = onAddServer
        }
    }

    Surface(
        onClick = onClick,
        shape = SubspaceShapeTokens.cardLead,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = actionDescription },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ServerFlagTile(activeProfile)
            ServerText(headline, activeProfile, Modifier.weight(1f))
            Icon(painterResource(SubspaceIcons.ChevronRight), contentDescription = null)
        }
    }
}

@Composable
private fun ServerText(
    headline: String,
    profile: StoredProfile?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.home_active_server_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (profile != null) {
            Text(
                text =
                stringResource(
                    R.string.home_active_server_meta,
                    protocolDisplayName(profile.protocol),
                    profile.transport.uppercase(),
                ),
                style = MaterialTheme.typography.bodySmall.mono(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The 52 dp tile: the flag in the profile's name when it has one, otherwise a server glyph. */
@Composable
private fun ServerFlagTile(profile: StoredProfile?) {
    Box(
        modifier =
        Modifier
            .size(52.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, SubspaceShapeTokens.tile),
        contentAlignment = Alignment.Center,
    ) {
        val flag = profile?.let { flagIn(it.name) }
        if (flag != null) {
            Text(text = flag, fontSize = 26.sp)
        } else {
            Icon(
                painterResource(SubspaceIcons.Dns),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
