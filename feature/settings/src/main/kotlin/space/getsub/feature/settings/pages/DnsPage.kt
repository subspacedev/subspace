// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.getsub.core.model.DnsTransport
import space.getsub.core.model.DnsValidation
import space.getsub.core.ui.component.BannerTone
import space.getsub.core.ui.component.ModeTile
import space.getsub.core.ui.component.ModeTiles
import space.getsub.core.ui.component.SectionTitle
import space.getsub.core.ui.component.TonalBanner
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.SubspaceShapeTokens
import space.getsub.core.ui.theme.mono
import space.getsub.feature.settings.R
import space.getsub.feature.settings.SettingsActions
import space.getsub.feature.settings.SettingsState

/** The app-level resolver, used while no routing profile supplies effective DNS behavior. */
@Composable
internal fun DnsPage(
    state: SettingsState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageScaffold(title = stringResource(R.string.settings_dns_title), onBack = onBack, modifier = modifier) {
        SettingsDnsSection(state = state, actions = actions)
    }
}

/** The page body: protocol tiles, then the resolver and bootstrap fields on a tonal card. */
@Composable
internal fun SettingsDnsSection(
    state: SettingsState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionTitle(stringResource(R.string.settings_dns_transport))
        ModeTiles(
            options =
            DnsTransport.entries.map { transport ->
                ModeTile(
                    icon = if (transport == DnsTransport.DOH) SubspaceIcons.Lock else SubspaceIcons.Dns,
                    label = transport.wireValue,
                )
            },
            selected = DnsTransport.entries.indexOf(state.dnsTransport),
            onSelect = { actions.onDnsTransportChanged(DnsTransport.entries[it]) },
        )

        val isDoh = state.dnsTransport == DnsTransport.DOH
        // Read by the bootstrap field when it leaves composition because the transport changed:
        // that is not a reason to commit what it held.
        val transportNow = rememberUpdatedState(state.dnsTransport)
        Surface(
            shape = SubspaceShapeTokens.card,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                DnsAddressField(state = state, actions = actions, isDoh = isDoh)
                if (isDoh) {
                    DnsBootstrapField(state = state, actions = actions, transportNow = transportNow)
                }
            }
        }

        if (state.dnsOverriddenByProfile) {
            TonalBanner(
                icon = SubspaceIcons.Info,
                text = stringResource(R.string.settings_dns_overridden),
                tone = BannerTone.Neutral,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun DnsAddressField(
    state: SettingsState,
    actions: SettingsActions,
    isDoh: Boolean,
) {
    var draft by remember(state.dnsAddress) { mutableStateOf(state.dnsAddress) }
    val commit = {
        if (shouldCommitDraft(draft, state.dnsAddress, isDnsAddressValid(draft, state.dnsTransport))) {
            actions.onDnsAddressChanged(draft)
        }
    }
    CommitDraftOnDispose(commit)
    OutlinedTextField(
        value = draft,
        onValueChange = { typed -> draft = typed },
        label = { Text(stringResource(R.string.settings_dns_address)) },
        isError = draft.isNotBlank() && !isDnsAddressValid(draft, state.dnsTransport),
        supportingText = {
            Text(
                stringResource(
                    if (isDoh) R.string.settings_dns_invalid_https else R.string.settings_dns_invalid_ip,
                ),
            )
        },
        textStyle = MaterialTheme.typography.bodySmall.mono(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier =
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                if (!focus.hasFocus && draft != state.dnsAddress) {
                    actions.onDnsAddressChanged(draft)
                }
            },
    )
}

@Composable
private fun DnsBootstrapField(
    state: SettingsState,
    actions: SettingsActions,
    transportNow: State<DnsTransport>,
) {
    var draft by remember(state.dnsBootstrapIp) { mutableStateOf(state.dnsBootstrapIp) }
    val commit = {
        if (shouldCommitDraft(draft, state.dnsBootstrapIp, isBootstrapValid(draft))) {
            actions.onDnsBootstrapIpChanged(draft)
        }
    }
    CommitDraftOnDispose { if (transportNow.value == DnsTransport.DOH) commit() }
    OutlinedTextField(
        value = draft,
        onValueChange = { typed -> draft = typed },
        label = { Text(stringResource(R.string.settings_dns_bootstrap)) },
        isError = !isBootstrapValid(draft) && draft.isNotBlank(),
        supportingText = { Text(stringResource(R.string.settings_dns_bootstrap_summary)) },
        textStyle = MaterialTheme.typography.bodySmall.mono(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .onFocusChanged { focus ->
                if (!focus.hasFocus && draft != state.dnsBootstrapIp) {
                    actions.onDnsBootstrapIpChanged(draft)
                }
            },
    )
}

/** The import parser's validators, so the settings screen cannot accept a different resolver shape. */
private fun isDnsAddressValid(
    value: String,
    transport: DnsTransport,
): Boolean =
    when (transport) {
        DnsTransport.DOH -> DnsValidation.isHttpsUrl(value)
        DnsTransport.DOU -> DnsValidation.isAddressLiteral(value)
    }

/** Blank clears the bootstrap address; anything else must be an address literal. */
private fun isBootstrapValid(value: String): Boolean = value.isBlank() || DnsValidation.isAddressLiteral(value)
