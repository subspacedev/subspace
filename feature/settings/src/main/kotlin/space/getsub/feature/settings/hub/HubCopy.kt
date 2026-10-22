// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.hub

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import space.getsub.core.model.DnsTransport
import space.getsub.feature.settings.R
import space.getsub.feature.settings.SettingsState
import space.getsub.feature.settings.XrayVersionState
import space.getsub.feature.settings.labelRes

// The hub rows' value text (M8.6 spec §4.4, design copy), rendered from HubValues.kt's pure values.

/** The DNS row's address: a DoH URL's host ("dns.cloudflare.com"), or the DoU address as written. */
internal fun dnsHubAddress(
    transport: DnsTransport,
    address: String,
): String =
    when (transport) {
        DnsTransport.DOH -> address.substringAfter("://").substringBefore('/').substringBefore('?')
        DnsTransport.DOU -> address
    }

@Composable
internal fun joined(parts: List<String>): String = parts.joinToString(stringResource(R.string.settings_hub_separator))

@Composable
internal fun routingText(state: SettingsState): String =
    when (val v = state.routingValue()) {
        is HubValue.Active -> stringResource(R.string.settings_hub_routing_active, v.name)
        else -> stringResource(R.string.settings_hub_off)
    }

@Composable
internal fun perAppText(state: SettingsState): String =
    when (val v = state.perAppValue()) {
        is HubValue.AllowList -> pluralStringResource(R.plurals.settings_hub_per_app_allow, v.count, v.count)
        is HubValue.DenyList -> pluralStringResource(R.plurals.settings_hub_per_app_deny, v.count, v.count)
        else -> stringResource(R.string.settings_hub_per_app_off)
    }

@Composable
internal fun latencyText(state: SettingsState): String {
    val (mode, timeout) = state.latencyValue()
    return stringResource(R.string.settings_hub_latency, stringResource(mode.labelRes()), timeout)
}

@Composable
internal fun dnsText(state: SettingsState): String {
    val (transport, address) = state.dnsValue()
    val host = dnsHubAddress(transport, address)
    return if (host.isBlank()) transport.wireValue else joined(listOf(transport.wireValue, host))
}

@Composable
internal fun aboutText(state: SettingsState): String =
    when (val xray = state.xrayVersion) {
        is XrayVersionState.Available -> stringResource(R.string.settings_hub_about, state.appVersion, xray.version)
        // Still loading, or the native call failed: the About page says which; the hub just leaves it out.
        XrayVersionState.Loading, XrayVersionState.Unavailable ->
            stringResource(R.string.settings_hub_about_app_only, state.appVersion)
    }

/** M9-A spec §2.3 (and M9-C): what is on. Both Off names the provider's settings, never "off". */
@StringRes
internal fun censorshipTextRes(state: SettingsState): Int =
    when {
        state.fragment.enabled && state.noise.enabled && state.preResolve.enabled ->
            R.string.settings_censorship_hub_all
        state.fragment.enabled && state.noise.enabled -> R.string.settings_censorship_hub_both
        state.fragment.enabled && state.preResolve.enabled -> R.string.settings_censorship_hub_fragment_preresolve
        state.noise.enabled && state.preResolve.enabled -> R.string.settings_censorship_hub_noise_preresolve
        state.fragment.enabled -> R.string.settings_censorship_hub_fragment
        state.noise.enabled -> R.string.settings_censorship_hub_noise
        state.preResolve.enabled -> R.string.settings_censorship_hub_preresolve
        else -> R.string.settings_censorship_hub_off
    }
