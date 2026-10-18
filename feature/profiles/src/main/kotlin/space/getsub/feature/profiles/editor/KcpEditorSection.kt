// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import space.getsub.core.model.KcpHeader
import space.getsub.core.model.KcpUnsupported
import space.getsub.core.ui.component.BannerTone
import space.getsub.core.ui.component.TonalBanner
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.feature.profiles.R

/** Identifies the "this build cannot run this mKCP setup" banner. */
internal const val EDITOR_KCP_NOT_CONNECTABLE_TEST_TAG = "editor-kcp-not-connectable"

/** Identifies the "Legacy obfuscation" switch. */
internal const val EDITOR_KCP_LEGACY_SWITCH_TEST_TAG = "editor-kcp-legacy-switch"

private const val NO_HEADER = "none"

/** The mKCP fields shown under the Network dropdown when mKCP is chosen. */
@Composable
internal fun KcpFields(
    state: EditorState,
    onChanged: (KcpDraft) -> Unit,
) {
    val draft = state.kcp
    val errors = state.kcpErrors
    Column(verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        draft.unsupported?.let { reason ->
            TonalBanner(
                icon = SubspaceIcons.Warning,
                text = stringResource(reason.messageRes()),
                tone = BannerTone.Warning,
                modifier = Modifier.testTag(EDITOR_KCP_NOT_CONNECTABLE_TEST_TAG),
            )
        }
        EditorField(
            label = stringResource(R.string.editor_kcp_mtu_label),
            value = draft.mtu,
            onValueChange = { onChanged(draft.copy(mtu = it)) },
            keyboardType = KeyboardType.Number,
            errorText = errorIf(KcpField.Mtu in errors, R.string.editor_kcp_mtu_error),
        )
        EditorField(
            label = stringResource(R.string.editor_kcp_tti_label),
            value = draft.tti,
            onValueChange = { onChanged(draft.copy(tti = it)) },
            keyboardType = KeyboardType.Number,
            errorText = errorIf(KcpField.Tti in errors, R.string.editor_kcp_tti_error),
        )
        // The seed is verbatim (ruling D2-R6): a space is a real seed, so nothing here trims it.
        EditorField(
            label = stringResource(R.string.editor_kcp_seed_label),
            value = draft.seed,
            onValueChange = { onChanged(draft.copy(seed = it)) },
            mono = true,
            secret = true,
        )
        EditorDropdownField(
            label = stringResource(R.string.editor_kcp_header_label),
            selected = draft.header?.wireName ?: NO_HEADER,
            options = listOf(NO_HEADER) + KcpHeader.entries.map { it.wireName },
            onSelected = { name ->
                onChanged(draft.copy(header = KcpHeader.entries.firstOrNull { it.wireName == name }))
            },
            optionLabel = { name -> if (name == NO_HEADER) stringResource(R.string.editor_kcp_header_none) else name },
        )
        LegacySwitch(draft, onChanged)
    }
}

@Composable
private fun LegacySwitch(
    draft: KcpDraft,
    onChanged: (KcpDraft) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.editor_kcp_legacy_label),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = draft.legacySwitchOn,
                onCheckedChange = { onChanged(draft.copy(legacyOriginal = it)) },
                enabled = draft.legacySwitchEnabled,
                modifier = Modifier.testTag(EDITOR_KCP_LEGACY_SWITCH_TEST_TAG),
            )
        }
        val hint =
            when {
                draft.legacySeedReplaces -> R.string.editor_kcp_legacy_seed_hint
                draft.legacyForced -> R.string.editor_kcp_legacy_forced_hint
                else -> null
            }
        if (hint != null) {
            Text(
                text = stringResource(hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun KcpUnsupported.messageRes(): Int =
    when (this) {
        KcpUnsupported.HttpHeader -> R.string.editor_kcp_http_header
        KcpUnsupported.UnknownHeader, KcpUnsupported.UnknownMask -> R.string.editor_kcp_unknown_mask
        KcpUnsupported.DnsHeaderDomain -> R.string.editor_kcp_dns_domain
        KcpUnsupported.InvalidSetting -> R.string.editor_kcp_invalid_setting
    }

/** HTTPUpgrade host and path. The path keeps a trailing `?ed=` exactly as typed. */
@Composable
internal fun HttpUpgradeFields(
    state: EditorState,
    onHostChanged: (String) -> Unit,
    onPathChanged: (String) -> Unit,
) {
    EditorField(
        label = stringResource(R.string.editor_httpupgrade_host_label),
        value = state.httpUpgradeHost,
        onValueChange = onHostChanged,
        mono = true,
    )
    EditorField(
        label = stringResource(R.string.editor_httpupgrade_path_label),
        value = state.httpUpgradePath,
        onValueChange = onPathChanged,
        mono = true,
    )
}
