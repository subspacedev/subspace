// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import space.getsub.core.model.WireGuardUnsupported
import space.getsub.core.ui.component.BannerTone
import space.getsub.core.ui.component.TonalBanner
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.feature.profiles.R

/** Identifies the "this build cannot run what the source set" banner. */
internal const val EDITOR_WIREGUARD_NOT_CONNECTABLE_TEST_TAG = "editor-wireguard-not-connectable"

/**
 * The WireGuard half of the typed editor: keys, interface addresses and tunnel settings. Address and port
 * (the peer endpoint) sit in the shared Server card. Stateless: every edit hands a whole new
 * [WireGuardDraft] to [onChanged]; the rules are in [WireGuardDraft], not here.
 */
@Composable
internal fun WireGuardSections(
    state: EditorState,
    onChanged: (WireGuardDraft) -> Unit,
) {
    val draft = state.wireGuard
    val errors = state.wireGuardErrors
    draft.notConnectableReason?.let { reason ->
        TonalBanner(
            icon = SubspaceIcons.Warning,
            text = stringResource(reason.messageRes()),
            tone = BannerTone.Warning,
            modifier = Modifier.testTag(EDITOR_WIREGUARD_NOT_CONNECTABLE_TEST_TAG),
        )
    }
    SectionCard(title = stringResource(R.string.editor_wireguard_section_keys)) {
        KeyFields(draft, errors, onChanged)
    }
    SectionCard(title = stringResource(R.string.editor_wireguard_section_tunnel)) {
        TunnelFields(draft, errors, onChanged)
    }
}

@Composable
private fun KeyFields(
    draft: WireGuardDraft,
    errors: Set<WireGuardField>,
    onChanged: (WireGuardDraft) -> Unit,
) {
    EditorField(
        label = stringResource(R.string.editor_wireguard_secret_key_label),
        value = draft.secretKey,
        onValueChange = { onChanged(draft.copy(secretKey = it)) },
        mono = true,
        secret = true,
        errorText = errorIf(WireGuardField.SecretKey in errors, R.string.editor_wireguard_key_error),
    )
    EditorField(
        label = stringResource(R.string.editor_wireguard_peer_key_label),
        value = draft.peerPublicKey,
        onValueChange = { onChanged(draft.copy(peerPublicKey = it)) },
        mono = true,
        errorText = errorIf(WireGuardField.PeerPublicKey in errors, R.string.editor_wireguard_key_error),
    )
    EditorField(
        label = stringResource(R.string.editor_wireguard_psk_label),
        value = draft.preSharedKey,
        onValueChange = { onChanged(draft.copy(preSharedKey = it)) },
        mono = true,
        secret = true,
        errorText = errorIf(WireGuardField.PreSharedKey in errors, R.string.editor_wireguard_key_error),
    )
}

@Composable
private fun TunnelFields(
    draft: WireGuardDraft,
    errors: Set<WireGuardField>,
    onChanged: (WireGuardDraft) -> Unit,
) {
    EditorField(
        label = stringResource(R.string.editor_wireguard_local_addresses_label),
        value = draft.localAddresses,
        onValueChange = { onChanged(draft.copy(localAddresses = it)) },
        mono = true,
        singleLine = false,
        errorText = errorIf(WireGuardField.LocalAddresses in errors, R.string.editor_wireguard_local_addresses_error),
        hint = stringResource(R.string.editor_wireguard_local_addresses_hint),
    )
    EditorField(
        label = stringResource(R.string.editor_wireguard_allowed_ips_label),
        value = draft.allowedIps,
        onValueChange = { onChanged(draft.copy(allowedIps = it)) },
        mono = true,
        singleLine = false,
        errorText = errorIf(WireGuardField.AllowedIps in errors, R.string.editor_wireguard_allowed_ips_error),
    )
    EditorField(
        label = stringResource(R.string.editor_wireguard_mtu_label),
        value = draft.mtu,
        onValueChange = { onChanged(draft.copy(mtu = it)) },
        keyboardType = KeyboardType.Number,
        errorText = errorIf(WireGuardField.Mtu in errors, R.string.editor_wireguard_mtu_error),
    )
    EditorField(
        label = stringResource(R.string.editor_wireguard_reserved_label),
        value = draft.reserved,
        onValueChange = { onChanged(draft.copy(reserved = it)) },
        mono = true,
        errorText = errorIf(WireGuardField.Reserved in errors, R.string.editor_wireguard_reserved_error),
    )
    EditorField(
        label = stringResource(R.string.editor_wireguard_keepalive_label),
        value = draft.keepAlive,
        onValueChange = { onChanged(draft.copy(keepAlive = it)) },
        keyboardType = KeyboardType.Number,
        errorText = errorIf(WireGuardField.Keepalive in errors, R.string.editor_wireguard_keepalive_error),
    )
}

private fun WireGuardUnsupported.messageRes(): Int =
    when (this) {
        WireGuardUnsupported.MultiplePeers -> R.string.editor_wireguard_multiple_peers
        WireGuardUnsupported.InvalidKey -> R.string.editor_wireguard_invalid_key
        WireGuardUnsupported.InvalidReserved -> R.string.editor_wireguard_invalid_reserved
        WireGuardUnsupported.UnsupportedMask -> R.string.editor_wireguard_unsupported_mask
        WireGuardUnsupported.InvalidMtu -> R.string.editor_wireguard_invalid_mtu
        WireGuardUnsupported.InvalidAllowedIp -> R.string.editor_wireguard_invalid_allowed_ips
    }
