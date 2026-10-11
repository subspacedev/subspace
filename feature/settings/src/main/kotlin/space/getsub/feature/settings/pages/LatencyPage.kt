// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import space.getsub.core.model.PingMode
import space.getsub.core.ui.component.ModeTile
import space.getsub.core.ui.component.ModeTiles
import space.getsub.core.ui.component.SectionTitle
import space.getsub.core.ui.component.SliderRow
import space.getsub.core.ui.component.SwitchRow
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.mono
import space.getsub.feature.settings.R
import space.getsub.feature.settings.SettingsActions
import space.getsub.feature.settings.SettingsState
import space.getsub.feature.settings.labelRes
import space.getsub.feature.settings.summaryRes

/** The timeout slider's bounds, the same 1–15 s the repository clamps to (M8.6 spec §4.4, fix S4). */
private const val MIN_TIMEOUT_SECONDS = 1
private const val MAX_TIMEOUT_SECONDS = 15
private val TIMEOUT_RANGE_SECONDS = MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS

/**
 * Method, check URL, timeout, and the two ping-on-launch switches.
 *
 * Two methods only: `icmp` needs root (Appendix D), and a GET-based proxy mode is not implementable
 * against libXray v26.7.11 — hence "Proxy (HEAD)" rather than a bare "Proxy", which would promise a
 * request this build cannot make.
 */
@Composable
internal fun LatencyPage(
    state: SettingsState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageScaffold(title = stringResource(R.string.settings_latency_section), onBack = onBack, modifier = modifier) {
        MethodTiles(state = state, actions = actions)
        CheckUrlField(state = state, actions = actions)
        TimeoutSlider(seconds = state.pingTimeoutSeconds, onChanged = actions.onPingTimeoutChanged)
        SwitchRow(
            icon = SubspaceIcons.Refresh,
            label = stringResource(R.string.settings_ping_on_launch),
            supporting = stringResource(R.string.settings_ping_on_launch_summary),
            checked = state.pingOnLaunch,
            onCheckedChange = actions.onPingOnLaunchChanged,
        )
        SwitchRow(
            icon = SubspaceIcons.Refresh,
            label = stringResource(R.string.settings_ping_on_launch_metered),
            supporting = stringResource(R.string.settings_ping_on_launch_metered_summary),
            checked = state.pingOnLaunchMetered,
            // Only meaningful when the launch run is on at all.
            enabled = state.pingOnLaunch,
            onCheckedChange = actions.onPingOnLaunchMeteredChanged,
        )
    }
}

@Composable
private fun MethodTiles(
    state: SettingsState,
    actions: SettingsActions,
) {
    SectionTitle(stringResource(R.string.settings_ping_mode))
    Text(
        text = stringResource(state.pingMode.summaryRes()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 10.dp),
    )
    ModeTiles(
        options =
        PingMode.entries.map { mode ->
            ModeTile(
                icon = if (mode == PingMode.TCP) SubspaceIcons.Speed else SubspaceIcons.NetworkPing,
                label = stringResource(mode.labelRes()),
            )
        },
        selected = PingMode.entries.indexOf(state.pingMode),
        onSelect = { actions.onPingModeChanged(PingMode.entries[it]) },
    )
}

/**
 * A plain field, not a picker: this is user-owned. `check-url-via-proxy` stays unwired precisely
 * because a provider choosing what this device fetches through the tunnel is the threat model the
 * ping design excludes, and honouring it would need an explicit-confirmation step that was not built.
 *
 * Edited locally and committed on focus loss, IME Done and leaving the page, rather than written
 * on every keystroke. The repository maps a stored blank back to the default, so a per-keystroke write meant
 * select-all-delete instantly repopulated the field and the user could never type a replacement
 * from scratch. It also put a Room write plus a full flow round-trip between every character.
 */
@Composable
private fun CheckUrlField(
    state: SettingsState,
    actions: SettingsActions,
) {
    var draft by remember(state.pingCheckUrl) { mutableStateOf(state.pingCheckUrl) }
    // No validator: the repository maps a blank or unusable value back to the default.
    val commit = {
        if (shouldCommitDraft(draft, state.pingCheckUrl, isValid = true)) {
            actions.onPingCheckUrlChanged(draft)
        }
    }
    CommitDraftOnDispose(commit)
    OutlinedTextField(
        value = draft,
        onValueChange = { typed -> draft = typed },
        label = { Text(stringResource(R.string.settings_ping_check_url)) },
        supportingText = { Text(stringResource(R.string.settings_ping_check_url_summary)) },
        textStyle = MaterialTheme.typography.bodySmall.mono(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onFocusChanged { focus ->
                if (!focus.hasFocus && draft != state.pingCheckUrl) {
                    actions.onPingCheckUrlChanged(draft)
                }
            },
    )
}

/**
 * The stored value is already clamped to 1–15 by the repository on read, so [seconds] is in range
 * in practice; `coerceIn` here is a display-only guard for the slider and never writes. Only a
 * drag calls [onChanged].
 */
@Composable
private fun TimeoutSlider(
    seconds: Int,
    onChanged: (Int) -> Unit,
) {
    val resources = LocalResources.current
    SliderRow(
        label = stringResource(R.string.settings_ping_timeout),
        value = seconds.coerceIn(TIMEOUT_RANGE_SECONDS),
        range = TIMEOUT_RANGE_SECONDS,
        valueText = { resources.getString(R.string.settings_ping_timeout_value, it) },
        onValueChange = onChanged,
    )
}
