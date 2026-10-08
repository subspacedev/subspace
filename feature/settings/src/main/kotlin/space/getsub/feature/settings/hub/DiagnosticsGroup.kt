// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.hub

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import space.getsub.core.ui.component.HubRow
import space.getsub.core.ui.component.SwitchRow
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.feature.settings.R
import space.getsub.feature.settings.SettingsState

/**
 * The hub's Diagnostics group (M8.6 spec §5.2): the session log, and the per-server traffic
 * breakdown switch.
 *
 * The switch's supporting text, [R.string.settings_breakdown_summary], is a security disclosure and
 * stays word for word: xray's metrics listener serves `/debug/vars` and the full pprof suite from
 * the same `http.ServeMux`, and Android loopback is not app-isolated, so any app that finds the
 * port can reach it while this is on. That is why the setting defaults to off (see
 * [space.getsub.core.data.SettingsRepository.perTagBreakdown]).
 *
 * The "applies to your next connection" line shows whenever a session is connected
 * ([SettingsState.perTagBreakdownSessionNoticeVisible], ruling R43): `TunnelService.startCore`
 * reads the setting once, at connect, and the running core keeps its listener until reconnect
 * whatever the switch now reads. The wording is true in either direction.
 */
@Composable
internal fun DiagnosticsGroup(
    state: SettingsState,
    onOpenSessionLog: () -> Unit,
    onPerTagBreakdownChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    HubGroup(modifier = modifier) {
        HubRow(
            icon = SubspaceIcons.ListAlt,
            label = stringResource(R.string.settings_logs_title),
            value =
            stringResource(
                when (state.sessionLogValue()) {
                    SessionLogValue.Recording -> R.string.settings_hub_log_recording
                    SessionLogValue.NotRecordingLightweight -> R.string.settings_hub_log_lightweight
                },
            ),
            onClick = onOpenSessionLog,
        )
        SwitchRow(
            icon = SubspaceIcons.SwapVert,
            label = stringResource(R.string.settings_breakdown_title),
            supporting = stringResource(R.string.settings_breakdown_summary),
            checked = state.perTagBreakdown,
            onCheckedChange = onPerTagBreakdownChange,
            note =
            if (state.perTagBreakdownSessionNoticeVisible) {
                stringResource(R.string.settings_breakdown_session_notice)
            } else {
                null
            },
        )
    }
}
