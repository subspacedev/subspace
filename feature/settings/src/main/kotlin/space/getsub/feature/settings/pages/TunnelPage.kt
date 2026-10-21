// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import space.getsub.core.data.TuningValue
import space.getsub.core.model.CidrRange
import space.getsub.core.model.MuxSpec
import space.getsub.core.model.ProxyAuthMode
import space.getsub.core.model.SniffingRuleWarning
import space.getsub.core.model.Udp443Mode
import space.getsub.core.ui.component.BannerTone
import space.getsub.core.ui.component.ModeTile
import space.getsub.core.ui.component.ModeTiles
import space.getsub.core.ui.component.SectionTitle
import space.getsub.core.ui.component.SubspaceDialog
import space.getsub.core.ui.component.SwitchRow
import space.getsub.core.ui.component.TonalBanner
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.mono
import space.getsub.feature.settings.R
import space.getsub.feature.settings.SettingsActions
import space.getsub.feature.settings.SettingsState

/**
 * Sniffing, mux, TUN MTU, excluded routes and local proxy sign-in (M9-E spec §1–§5).
 *
 * Each tuning section follows M9-A's Censorship page: your switch On wins; Off keeps your values
 * and lets the active server's own subscription decide, shown as "From your subscription: …".
 * Unlike that page, edits are a draft with one Save: applying any of these restarts the session
 * ([space.getsub.service.TunnelClient.reapplyTuning]), and that should happen once per save.
 */
@Composable
internal fun TunnelPage(
    state: SettingsState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Reset to what is stored whenever that changes, which includes right after a save.
    var draft by remember(state.tunnel) { mutableStateOf(state.tunnel) }
    val dirty = draft != state.tunnel
    // Plain remember, as the routing editors' discard guard: the draft itself is not saved.
    var asking by remember { mutableStateOf(false) }
    BackHandler(enabled = dirty) { asking = true }
    val errors = draft.errors()
    val provider = state.tunnelWithoutYourOverrides

    PageScaffold(
        title = stringResource(R.string.settings_tunnel_title),
        onBack = { if (dirty) asking = true else onBack() },
        modifier = modifier,
    ) {
        SniffingSection(draft, provider?.sniffing, state.sniffingOffWarning) { draft = it }
        MuxSection(draft, provider?.mux, errors) { draft = it }
        MtuSection(draft, provider?.tunMtu, errors) { draft = it }
        RoutesSection(draft, provider?.excludedRoutes, errors) { draft = it }
        ProxyAuthSection(draft, errors) { draft = it }
        SaveRow(
            enabled = draft.canSave(state.tunnel),
            restarts = state.sessionConnected,
            onSave = { actions.onTunnelSettingsSaved(draft) },
        )
    }

    if (asking) {
        SubspaceDialog(
            title = stringResource(R.string.settings_tunnel_discard_title),
            body = stringResource(R.string.settings_tunnel_discard_body),
            confirmLabel = stringResource(R.string.settings_tunnel_discard_confirm),
            onConfirm = {
                asking = false
                onBack()
            },
            dismissLabel = stringResource(R.string.settings_tunnel_discard_cancel),
            onDismiss = { asking = false },
            destructive = true,
        )
    }
}

private typealias Edit = (TunnelDraft) -> Unit

@Composable
private fun SniffingSection(
    draft: TunnelDraft,
    provider: TuningValue<Boolean>?,
    offWarning: SniffingRuleWarning,
    edit: Edit,
) {
    val value = draft.sniffing
    SectionTitle(stringResource(R.string.settings_tunnel_sniffing_title))
    OverrideRow(value.override, providerLine(provider) { onOff(it) }) {
        edit(draft.copy(sniffing = value.copy(override = it)))
    }
    if (value.override) {
        SwitchRow(
            icon = null,
            label = stringResource(R.string.settings_tunnel_sniffing_label),
            supporting = stringResource(R.string.settings_tunnel_sniffing_summary),
            checked = value.enabled,
            onCheckedChange = { edit(draft.copy(sniffing = value.copy(enabled = it))) },
        )
    }
    // The effective value: yours when your switch is on, else the server's subscription (default on).
    val effective = if (value.override) value.enabled else provider?.value ?: true
    if (value.override && !value.enabled) {
        TonalBanner(
            icon = SubspaceIcons.Info,
            text = stringResource(R.string.settings_tunnel_sniffing_fakedns_note),
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
    val warning =
        when (offWarning.takeUnless { effective }) {
            SniffingRuleWarning.DomainRulesStop -> R.string.settings_tunnel_sniffing_warning
            SniffingRuleWarning.FakeDnsLookupsOnly -> R.string.settings_tunnel_sniffing_warning_fakedns
            SniffingRuleWarning.None, null -> null
        }
    if (warning != null) {
        TonalBanner(
            icon = SubspaceIcons.Warning,
            text = stringResource(warning),
            tone = BannerTone.Warning,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun MuxSection(
    draft: TunnelDraft,
    provider: TuningValue<MuxSpec?>?,
    errors: TunnelErrors,
    edit: Edit,
) {
    val value = draft.mux
    SectionTitle(stringResource(R.string.settings_tunnel_mux_title))
    OverrideRow(value.override, providerLine(provider) { muxText(it) }) {
        edit(draft.copy(mux = value.copy(override = it)))
    }
    if (!value.override) return
    SwitchRow(
        icon = null,
        label = stringResource(R.string.settings_tunnel_mux_label),
        supporting = stringResource(R.string.settings_tunnel_mux_summary),
        checked = value.enabled,
        onCheckedChange = { edit(draft.copy(mux = value.copy(enabled = it))) },
    )
    if (!value.enabled) return
    TunnelField(
        label = R.string.settings_tunnel_mux_tcp,
        value = value.tcpConnections,
        error = errors.fields[TunnelField.MuxTcp],
        hint = R.string.settings_tunnel_mux_tcp_hint,
        number = true,
    ) { edit(draft.copy(mux = value.copy(tcpConnections = it))) }
    TunnelField(
        label = R.string.settings_tunnel_mux_xudp,
        value = value.xudpConnections,
        error = errors.fields[TunnelField.MuxXudp],
        hint = R.string.settings_tunnel_mux_xudp_hint,
        number = true,
    ) { edit(draft.copy(mux = value.copy(xudpConnections = it))) }
    SectionTitle(stringResource(R.string.settings_tunnel_mux_udp443))
    RadioList(
        choices =
        Udp443Mode.entries.map { mode ->
            RadioChoice(
                label = stringResource(mode.labelRes()),
                selected = value.quic == mode.wire,
                onSelect = { edit(draft.copy(mux = value.copy(quic = mode.wire))) },
            )
        },
    )
}

@Composable
private fun MtuSection(
    draft: TunnelDraft,
    provider: TuningValue<Int>?,
    errors: TunnelErrors,
    edit: Edit,
) {
    val value = draft.mtu
    SectionTitle(stringResource(R.string.settings_tunnel_mtu_title))
    OverrideRow(value.override, providerLine(provider) { it.toString() }) {
        edit(draft.copy(mtu = value.copy(override = it)))
    }
    if (!value.override) return
    TunnelField(
        label = R.string.settings_tunnel_mtu,
        value = value.value,
        error = errors.fields[TunnelField.Mtu],
        hint = R.string.settings_tunnel_mtu_hint,
        number = true,
    ) { edit(draft.copy(mtu = value.copy(value = it))) }
}

@Composable
private fun RoutesSection(
    draft: TunnelDraft,
    provider: TuningValue<List<CidrRange>>?,
    errors: TunnelErrors,
    edit: Edit,
) {
    val value = draft.routes
    SectionTitle(stringResource(R.string.settings_tunnel_routes_title))
    OverrideRow(value.override, providerLine(provider) { routesText(it) }) {
        edit(draft.copy(routes = value.copy(override = it)))
    }
    if (!value.override) return
    val lineErrors =
        errors.routeLines.entries.sortedBy { it.key }.map { (line, error) ->
            when (error) {
                RouteLineError.NotARange -> stringResource(R.string.settings_tunnel_routes_bad_line, line + 1)
                RouteLineError.WholeInternet -> stringResource(R.string.settings_tunnel_routes_whole_line, line + 1)
            }
        }
    OutlinedTextField(
        value = value.ranges,
        onValueChange = { edit(draft.copy(routes = value.copy(ranges = it))) },
        label = { Text(stringResource(R.string.settings_tunnel_routes)) },
        isError = lineErrors.isNotEmpty(),
        supportingText = {
            Text(lineErrors.joinToString("\n").ifEmpty { stringResource(R.string.settings_tunnel_routes_hint) })
        },
        textStyle = MaterialTheme.typography.bodySmall.mono(),
        minLines = 3,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("field-routes"),
    )
}

@Composable
private fun ProxyAuthSection(
    draft: TunnelDraft,
    errors: TunnelErrors,
    edit: Edit,
) {
    val value = draft.proxyAuth
    SectionTitle(stringResource(R.string.settings_tunnel_proxy_title))
    Text(
        text = stringResource(R.string.settings_tunnel_proxy_summary),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    ModeTiles(
        options =
        listOf(
            ModeTile(icon = SubspaceIcons.Sync, label = stringResource(R.string.settings_tunnel_proxy_auto)),
            ModeTile(icon = SubspaceIcons.Edit, label = stringResource(R.string.settings_tunnel_proxy_manual)),
        ),
        selected = ProxyAuthMode.entries.indexOf(value.mode),
        onSelect = { index -> edit(draft.copy(proxyAuth = value.copy(mode = ProxyAuthMode.entries[index]))) },
    )
    when (value.mode) {
        ProxyAuthMode.Auto ->
            Text(
                text = stringResource(R.string.settings_tunnel_proxy_auto_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        ProxyAuthMode.Manual -> {
            TunnelField(
                label = R.string.settings_tunnel_proxy_user,
                value = value.manualUser,
                error = errors.fields[TunnelField.ProxyUser],
            ) { edit(draft.copy(proxyAuth = value.copy(manualUser = it))) }
            TunnelField(
                label = R.string.settings_tunnel_proxy_pass,
                value = value.manualPass,
                error = errors.fields[TunnelField.ProxyPass],
                secret = true,
            ) { edit(draft.copy(proxyAuth = value.copy(manualPass = it))) }
        }
    }
}

@Composable
private fun OverrideRow(
    checked: Boolean,
    providerLine: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchRow(
        icon = SubspaceIcons.Tune,
        label = stringResource(R.string.settings_tunnel_override),
        supporting = if (checked) stringResource(R.string.settings_tunnel_override_on) else providerLine,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

// DEPRECATION: as EditorField in :feature:profiles, the secure field's replacement owns a
// TextFieldState, while this page's draft is a plain String; PasswordVisualTransformation masks it.
@Suppress("LongParameterList", "DEPRECATION")
@Composable
private fun TunnelField(
    @StringRes label: Int,
    value: String,
    error: TunnelFieldError?,
    @StringRes hint: Int? = null,
    number: Boolean = false,
    secret: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }
    val labelText = stringResource(label)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(labelText) },
        isError = error != null,
        supportingText =
        when {
            error != null -> {
                { Text(errorText(labelText, error)) }
            }
            hint != null -> {
                { Text(stringResource(hint)) }
            }
            else -> null
        },
        trailingIcon =
        if (secret) {
            {
                TextButton(onClick = { revealed = !revealed }) {
                    Text(
                        stringResource(
                            if (revealed) R.string.settings_tunnel_proxy_hide else R.string.settings_tunnel_proxy_show,
                        ),
                    )
                }
            }
        } else {
            null
        },
        visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = MaterialTheme.typography.bodySmall.mono(),
        singleLine = true,
        keyboardOptions =
        KeyboardOptions(
            keyboardType =
            when {
                secret -> KeyboardType.Password
                number -> KeyboardType.Number
                else -> KeyboardType.Text
            },
        ),
        // Not autofillable: these are this app's own settings, and the sign-in fields are the local
        // proxy's account, not a website login a password manager should offer to save
        // (device run 2026-10-22).
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("field-$label")
            .semantics { contentDataType = ContentDataType.None },
    )
}

@Composable
private fun SaveRow(
    enabled: Boolean,
    restarts: Boolean,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onSave, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.settings_tunnel_save))
        }
        if (restarts) {
            Text(
                text = stringResource(R.string.settings_tunnel_save_restarts),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
